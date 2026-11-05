<script setup lang="ts">
// 上传报告（FR-004/FR-005）：先建草稿，再一文件一请求并发上传（并发 2），单文件可重试
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage, fieldErrors } from '../api'
import { reportApi, type ReportKind } from '../api/report'
import { ocrApi } from '../api/ocr'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'
import { canGeneratePreview, createPreview } from '../utils/imageCompress'
import { REJECT_TEXT, checkUploadFile } from '../utils/fileAccept'
import PhotoPicker from '../components/report/PhotoPicker.vue'
import PhotoQueueList from '../components/report/PhotoQueueList.vue'
import DuplicateDialog from '../components/report/DuplicateDialog.vue'
import type { QueueItem } from '../components/report/types'

const MAX_FILES = 20
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
const conclusionError = ref('')
const CONCLUSION_MAX = 4000
const route = useRoute()
const initialKind = typeof route.query.kind === 'string' ? route.query.kind.toUpperCase() : ''
const reportKind = ref<ReportKind>(
  initialKind === 'IMAGING' || initialKind === 'UNSTRUCTURED' ? initialKind : 'STRUCTURED',
)
const conclusionText = ref('')
const isStructured = computed(() => reportKind.value === 'STRUCTURED')
const KIND_OPTIONS: { value: ReportKind; label: string; hint: string }[] = [
  { value: 'STRUCTURED', label: '检验报告', hint: '血常规、肝功能等带数值的报告，可识别指标' },
  { value: 'IMAGING', label: '影像报告', hint: '超声、CT、X 光、核磁等，保存原件与结论' },
  { value: 'UNSTRUCTURED', label: '其他报告', hint: '病历、诊断证明等，仅保存原件与说明' },
]
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
    const reason = checkUploadFile(file)
    if (reason) {
      rejected.push(`${file.name}（${REJECT_TEXT[reason]}）`)
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
  conclusionError.value = ''
  if (!isStructured.value && Array.from(conclusionText.value.trim()).length > CONCLUSION_MAX) {
    conclusionError.value = `结论不能超过 ${CONCLUSION_MAX} 个字符`
    return
  }
  creating.value = true
  try {
    const profileId = profile.profile?.id
    if (!profileId) throw new Error('profile missing')
    const report = await reportApi.create(profileId, {
      title: title.value.trim() || undefined,
      examinationDate: examinationDate.value || undefined,
      reportKind: reportKind.value,
      conclusionText: isStructured.value ? undefined : conclusionText.value.trim() || undefined,
    })
    reportId.value = report.id
  } catch (error) {
    const errors = fieldErrors(error)
    titleError.value = errors.title ?? ''
    dateError.value = errors.examinationDate ?? ''
    conclusionError.value = errors.conclusionText ?? ''
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

const uploadedCount = computed(() => items.value.filter((item) => item.status === 'done').length)
const ocrConsent = ref(false)
const proceeding = ref(false)

/** 上传完成后的下一步：用户明确勾选同意才提交第三方 OCR（NFR-002），否则可转手动录入 */
async function onProceed(mode: 'ocr' | 'manual') {
  if (reportId.value === null || proceeding.value) return
  if (mode === 'ocr' && !ocrConsent.value) return
  proceeding.value = true
  message.value = ''
  try {
    if (mode === 'ocr') await ocrApi.submit(reportId.value)
    else await ocrApi.manualEntry(reportId.value)
    await router.push({ name: 'report-review', params: { reportId: String(reportId.value) } })
  } catch (error) {
    message.value = errorMessage(error, mode === 'ocr' ? '提交识别失败，可稍后重试或手动录入' : '进入手动录入失败，请稍后重试')
  } finally {
    proceeding.value = false
  }
}

/** 影像/其他报告：不走 OCR 与指标核对，上传完成后直接归档（任务二） */
async function onArchive() {
  if (reportId.value === null || proceeding.value) return
  proceeding.value = true
  message.value = ''
  try {
    await reportApi.completeImaging(reportId.value)
    await router.push({ name: 'records', query: { kind: reportKind.value } })
  } catch (error) {
    message.value = errorMessage(error, '归档失败，请稍后重试')
  } finally {
    proceeding.value = false
  }
}

onBeforeUnmount(() => {
  for (const item of items.value) if (item.thumbUrl) URL.revokeObjectURL(item.thumbUrl)
})
</script>

<template>
  <main class="home-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'home' }">← 返回首页</RouterLink>
      <p class="brand">HealthTrace</p>
    </header>

    <section class="home-card upload-card" aria-labelledby="upload-title">
      <h1 id="upload-title">上传检查报告</h1>
      <p class="subtitle">一份报告可包含多页照片或 PDF，原件会完整保存。</p>

      <p v-if="!emailVerified" class="notice notice-error" role="alert">
        请先验证邮箱：验证后才能上传检查报告。
      </p>

      <form v-else @submit.prevent="onStart">
        <fieldset class="field kind-picker" data-testid="upload-kind">
          <legend>报告类型</legend>
          <label
            v-for="option in KIND_OPTIONS"
            :key="option.value"
            class="kind-option"
            :class="{ 'kind-option-active': reportKind === option.value }"
          >
            <input
              v-model="reportKind"
              type="radio"
              name="report-kind"
              :value="option.value"
              :disabled="started"
              :data-testid="`upload-kind-${option.value}`"
            />
            <span class="kind-option-text">
              <strong>{{ option.label }}</strong>
              <span class="field-hint">{{ option.hint }}</span>
            </span>
          </label>
        </fieldset>

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

        <div v-if="!isStructured" class="field">
          <label for="report-conclusion">{{ reportKind === 'IMAGING' ? '检查结论 / 印象（可选）' : '说明（可选）' }}</label>
          <textarea
            id="report-conclusion"
            v-model="conclusionText"
            rows="4"
            data-testid="upload-conclusion"
            placeholder="例如：肝胆胰脾未见明显异常"
            :disabled="started"
            :aria-invalid="conclusionError ? 'true' : undefined"
            :aria-describedby="conclusionError ? 'report-conclusion-error' : undefined"
          ></textarea>
          <p v-if="conclusionError" id="report-conclusion-error" class="field-error">{{ conclusionError }}</p>
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
        <p v-if="allSettled && isStructured" class="notice notice-success" role="status">上传完成，报告已加入首页待处理列表。</p>

        <div v-if="allSettled && uploadedCount > 0 && !isStructured" class="ocr-next">
          <p class="notice notice-success" role="status">上传完成，确认无误后归档到档案。</p>
          <button
            class="btn-primary"
            type="button"
            data-testid="upload-archive"
            :disabled="proceeding"
            @click="onArchive"
          >
            {{ proceeding ? '处理中…' : '完成归档' }}
          </button>
        </div>

        <div v-if="allSettled && uploadedCount > 0 && isStructured" class="ocr-next">
          <label class="declaration">
            <input v-model="ocrConsent" type="checkbox" :disabled="proceeding" />
            <span>我同意将本报告的图片发送至第三方 OCR 服务（百度医疗检验报告单识别）进行识别，识别结果需逐项核对后才会保存。</span>
          </label>
          <button
            class="btn-primary"
            type="button"
            :disabled="!ocrConsent || proceeding"
            @click="onProceed('ocr')"
          >
            {{ proceeding ? '处理中…' : '开始识别' }}
          </button>
          <button class="btn-secondary" type="button" :disabled="proceeding" @click="onProceed('manual')">
            不使用 OCR，手动录入
          </button>
        </div>

        <button v-if="!started" class="btn-primary" type="submit" :disabled="creating || items.length === 0">
          {{ creating ? '创建中…' : `开始上传（${items.length} 个文件）` }}
        </button>
        <button
          v-else
          class="btn-ghost btn-block"
          type="button"
          :disabled="busy || !allSettled || proceeding"
          @click="router.push({ name: 'home' })"
        >
          {{ allSettled ? '稍后处理，返回首页' : '上传中，请稍候…' }}
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
