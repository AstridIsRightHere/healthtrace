<script setup lang="ts">
// 上传报告（FR-004/FR-005）：先建草稿，再一文件一请求并发上传（并发 2），单文件可重试
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { errorMessage, fieldErrors } from '../api'
import { reportApi } from '../api/report'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'
import { canGeneratePreview, createPreview } from '../utils/imageCompress'
import PhotoPicker from '../components/report/PhotoPicker.vue'
import PhotoQueueList from '../components/report/PhotoQueueList.vue'
import DuplicateDialog from '../components/report/DuplicateDialog.vue'
import type { QueueItem } from '../components/report/types'

const MAX_FILES = 20
const MAX_FILE_BYTES = 25 * 1024 * 1024
const CONCURRENCY = 2

const auth = useAuthStore()
const profile = useProfileStore()
const router = useRouter()

const title = ref('')
const examinationDate = ref('')
const items = ref<QueueItem[]>([])
const reportId = ref<number | null>(null)
const started = ref(false)
const creating = ref(false)
const message = ref('')
const titleError = ref('')
const dateError = ref('')
let keySeq = 0

const today = new Date().toLocaleDateString('sv-SE') // yyyy-MM-dd（本地日期）
const emailVerified = computed(() => auth.user?.emailVerified ?? false)
const remaining = computed(() => MAX_FILES - items.value.length)
const activeDuplicate = computed(() => items.value.find((item) => item.status === 'duplicate') ?? null)
const allSettled = computed(
  () => started.value && items.value.every((item) => item.status === 'done' || item.status === 'skipped'),
)
const busy = computed(() => items.value.some((item) => item.status === 'preparing' || item.status === 'uploading'))

function onPick(files: File[]) {
  message.value = ''
  const rejected: string[] = []
  for (const file of files) {
    if (items.value.length >= MAX_FILES) {
      rejected.push(`${file.name}（超过 20 个）`)
      continue
    }
    if (file.size > MAX_FILE_BYTES) {
      rejected.push(`${file.name}（超过 25 MB）`)
      continue
    }
    items.value.push(
      reactive<QueueItem>({
        key: `f${++keySeq}`,
        file,
        thumbUrl: canGeneratePreview(file) ? URL.createObjectURL(file) : null,
        status: 'waiting',
        progress: 0,
        error: '',
        existingReportIds: [],
        pageOrder: null,
      }),
    )
  }
  if (rejected.length) message.value = `以下文件未加入：${rejected.join('、')}`
}

function onMove(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const list = items.value
  ;[list[index], list[target]] = [list[target]!, list[index]!]
}

function onRemove(index: number) {
  const [removed] = items.value.splice(index, 1)
  if (removed?.thumbUrl) URL.revokeObjectURL(removed.thumbUrl)
}

async function uploadOne(item: QueueItem, decision: 'ASK' | 'KEEP' = 'ASK') {
  if (reportId.value === null || item.pageOrder === null) return
  item.error = ''
  item.progress = 0
  try {
    item.status = 'preparing'
    const preview = await createPreview(item.file)
    item.status = 'uploading'
    const result = await reportApi.uploadPhoto(reportId.value, {
      original: item.file,
      preview,
      pageOrder: item.pageOrder,
      duplicateDecision: decision,
      onProgress: (percent) => (item.progress = percent),
    })
    if (result.result === 'DUPLICATE_SUSPECTED') {
      item.existingReportIds = result.existingReportIds ?? []
      item.status = 'duplicate'
    } else {
      item.progress = 100
      item.status = 'done'
    }
  } catch (error) {
    item.status = 'failed'
    item.error = errorMessage(error, '上传失败，请重试')
  }
}

/** 简单的并发池：最多 CONCURRENCY 个文件同时上传 */
async function runQueue(queue: QueueItem[]) {
  let cursor = 0
  const worker = async () => {
    while (cursor < queue.length) {
      const item = queue[cursor++]!
      await uploadOne(item)
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, queue.length) }, worker))
}

async function onStart() {
  if (started.value || creating.value || items.value.length === 0) return
  message.value = ''
  titleError.value = ''
  dateError.value = ''
  creating.value = true
  try {
    const profileId = profile.profile?.id
    if (!profileId) throw new Error('profile missing')
    const report = await reportApi.create(profileId, {
      title: title.value.trim() || undefined,
      examinationDate: examinationDate.value || undefined,
    })
    reportId.value = report.id
  } catch (error) {
    const errors = fieldErrors(error)
    titleError.value = errors.title ?? ''
    dateError.value = errors.examinationDate ?? ''
    message.value = errorMessage(error, '创建报告失败，请稍后重试')
    return
  } finally {
    creating.value = false
  }
  // 页序在开始时按当前排序锁定，重试沿用
  items.value.forEach((item, index) => (item.pageOrder = index + 1))
  started.value = true
  await runQueue([...items.value])
}

function onRetry(index: number) {
  const item = items.value[index]
  if (item && item.status === 'failed') void uploadOne(item)
}

function onDuplicateKeep() {
  const item = activeDuplicate.value
  if (item) void uploadOne(item, 'KEEP')
}

function onDuplicateSkip() {
  const item = activeDuplicate.value
  if (item) item.status = 'skipped'
}

onBeforeUnmount(() => {
  for (const item of items.value) if (item.thumbUrl) URL.revokeObjectURL(item.thumbUrl)
})
</script>

<template>
  <main class="home-page">
    <header class="home-header">
      <RouterLink class="btn-secondary" :to="{ name: 'home' }">返回首页</RouterLink>
      <p class="brand">HealthTrace</p>
    </header>

    <section class="home-card upload-card" aria-labelledby="upload-title">
      <h1 id="upload-title">上传检查报告</h1>
      <p class="subtitle">一份报告可包含多页照片或 PDF，原件会完整保存。</p>

      <p v-if="!emailVerified" class="notice notice-error" role="alert">
        请先验证邮箱：验证后才能上传检查报告。
      </p>

      <form v-else @submit.prevent="onStart">
        <div class="field">
          <label for="report-title">报告标题（可选）</label>
          <input
            id="report-title"
            v-model="title"
            type="text"
            maxlength="120"
            placeholder="例如：血常规复查"
            :disabled="started"
            :aria-invalid="titleError ? 'true' : undefined"
            :aria-describedby="titleError ? 'report-title-error' : undefined"
          />
          <p v-if="titleError" id="report-title-error" class="field-error">{{ titleError }}</p>
        </div>

        <div class="field">
          <label for="report-date">检查日期（可选）</label>
          <input
            id="report-date"
            v-model="examinationDate"
            type="date"
            :max="today"
            :disabled="started"
            :aria-invalid="dateError ? 'true' : undefined"
            :aria-describedby="dateError ? 'report-date-error' : undefined"
          />
          <p v-if="dateError" id="report-date-error" class="field-error">{{ dateError }}</p>
        </div>

        <PhotoPicker v-if="!started" :remaining="remaining" :disabled="creating" @pick="onPick" />

        <PhotoQueueList
          v-if="items.length"
          :items="items"
          :locked="started"
          @move="onMove"
          @remove="onRemove"
          @retry="onRetry"
        />

        <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
        <p v-if="allSettled" class="notice notice-success" role="status">上传完成，报告已加入首页待处理列表。</p>

        <button v-if="!started" class="btn-primary" type="submit" :disabled="creating || items.length === 0">
          {{ creating ? '创建中…' : `开始上传（${items.length} 个文件）` }}
        </button>
        <button
          v-else
          class="btn-primary"
          type="button"
          :disabled="busy || !allSettled"
          @click="router.push({ name: 'home' })"
        >
          {{ allSettled ? '完成' : '上传中，请稍候…' }}
        </button>
      </form>
    </section>

    <DuplicateDialog
      v-if="activeDuplicate"
      :key="activeDuplicate.key"
      :file-name="activeDuplicate.file.name"
      :existing-count="activeDuplicate.existingReportIds.length"
      @keep="onDuplicateKeep"
      @skip="onDuplicateSkip"
    />
  </main>
</template>
