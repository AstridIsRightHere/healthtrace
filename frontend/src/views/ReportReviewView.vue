<script setup lang="ts">
// 草稿核对页（FR-006/FR-007/FR-014/FR-015）：左侧原图对照，右侧可编辑指标表；整页确认 + 二次确认后才进入图表
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage, fieldErrors } from '../api'
import { ocrApi, ocrErrorText, type DraftView } from '../api/ocr'
import PhotoViewer from '../components/review/PhotoViewer.vue'
import ObservationTable from '../components/review/ObservationTable.vue'
import RawTextPanel from '../components/review/RawTextPanel.vue'
import ConfirmDialog from '../components/review/ConfirmDialog.vue'
import type { EditableRow } from '../components/review/types'

const POLL_INTERVAL_MS = 2000
const POLL_TIMEOUT_MS = 60_000

const route = useRoute()
const router = useRouter()
const reportId = Number(route.params.reportId)

const draft = ref<DraftView | null>(null)
const rows = ref<EditableRow[]>([])
const examinationDate = ref('')
const loading = ref(true)
const loadError = ref('')
const message = ref('')
const errors = ref<Record<string, string>>({})
const actionBusy = ref(false)
const showConfirm = ref(false)
const submitting = ref(false)
const pollTimedOut = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollStartedAt = 0
let keySeq = 0

const today = new Date().toLocaleDateString('sv-SE')
const status = computed(() => draft.value?.report.processingStatus ?? null)
const recognizing = computed(() => status.value === 'RECOGNIZING')
const failed = computed(() => status.value === 'FAILED')
const editable = computed(() => status.value === 'PENDING_CONFIRMATION')
const confirmed = computed(() => status.value === 'CONFIRMED')
const failureText = computed(() => ocrErrorText(draft.value?.ocrJob?.errorCode))
const retryHint = computed(() => {
  const job = draft.value?.ocrJob
  if (!job || job.status !== 'PENDING' || job.attemptCount === 0) return ''
  return `第 ${job.attemptCount} 次识别未成功，系统将自动重试（最多 ${job.maxAttempts} 次）。`
})

function toRows(view: DraftView): EditableRow[] {
  return view.observations.map((o) => ({
    key: `r${++keySeq}`,
    id: o.id,
    originalName: o.originalName,
    originalValue: o.originalValue,
    unitText: o.unitText ?? '',
    referenceText: o.referenceText ?? '',
    sourcePhotoId: o.sourcePhotoId,
    confidence: o.confidence,
  }))
}

function apply(view: DraftView) {
  draft.value = view
  rows.value = toRows(view)
  examinationDate.value = view.report.examinationDate ?? ''
}

async function load() {
  try {
    apply(await ocrApi.draft(reportId))
    loadError.value = ''
  } catch (error) {
    loadError.value = errorMessage(error, '报告加载失败，请稍后刷新')
  } finally {
    loading.value = false
  }
  schedulePoll()
}

function stopPoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

/** 识别中每 2 秒刷新一次，最长 60 秒；超时后停止并允许手动刷新 */
function schedulePoll() {
  stopPoll()
  if (!recognizing.value) return
  if (pollStartedAt === 0) pollStartedAt = Date.now()
  if (Date.now() - pollStartedAt > POLL_TIMEOUT_MS) {
    pollTimedOut.value = true
    return
  }
  pollTimer = setTimeout(load, POLL_INTERVAL_MS)
}

function restartPoll() {
  pollStartedAt = 0
  pollTimedOut.value = false
  void load()
}

async function onRetryOcr() {
  if (actionBusy.value) return
  actionBusy.value = true
  message.value = ''
  try {
    await ocrApi.submit(reportId)
    restartPoll()
  } catch (error) {
    message.value = errorMessage(error, '提交识别失败，可稍后重试或手动录入')
  } finally {
    actionBusy.value = false
  }
}

async function onManualEntry() {
  if (actionBusy.value) return
  actionBusy.value = true
  message.value = ''
  try {
    await ocrApi.manualEntry(reportId)
    await load()
  } catch (error) {
    message.value = errorMessage(error, '进入手动录入失败，请稍后重试')
  } finally {
    actionBusy.value = false
  }
}

function onEdit(index: number) {
  // 人工修改后不再展示 OCR 置信度提示（后端同样置空）
  const row = rows.value[index]
  if (row) row.confidence = null
}

function onRemove(index: number) {
  rows.value.splice(index, 1)
  errors.value = {}
}

function onAdd() {
  rows.value.push({
    key: `r${++keySeq}`,
    id: null,
    originalName: '',
    originalValue: '',
    unitText: '',
    referenceText: '',
    sourcePhotoId: null,
    confidence: null,
  })
}

/** 前端预校验，与后端规则一致；最终以后端 HT-1001 字段错误为准 */
function validate(): boolean {
  const next: Record<string, string> = {}
  if (!examinationDate.value) next.examinationDate = '请填写检查日期'
  if (rows.value.length === 0) next.observations = '请至少录入一项检验结果'
  rows.value.forEach((row, i) => {
    if (!row.originalName.trim()) next[`observations[${i}].originalName`] = '请填写项目名称'
    if (!row.originalValue.trim()) next[`observations[${i}].originalValue`] = '请填写结果'
  })
  errors.value = next
  return Object.keys(next).length === 0
}

function onRequestConfirm() {
  message.value = ''
  if (validate()) showConfirm.value = true
  else message.value = '还有内容需要补充，请检查标红的字段。'
}

async function onConfirm() {
  const view = draft.value
  if (!view || submitting.value) return
  submitting.value = true
  try {
    await ocrApi.confirm(reportId, {
      version: view.report.version,
      examinationDate: examinationDate.value,
      observations: rows.value.map((row) => ({
        id: row.id ?? undefined,
        originalName: row.originalName.trim(),
        originalValue: row.originalValue.trim(),
        unitText: row.unitText.trim() || null,
        referenceText: row.referenceText.trim() || null,
        sourcePhotoId: row.sourcePhotoId,
      })),
    })
    showConfirm.value = false
    // 回到首页：待处理列表重新挂载并刷新
    await router.push({ name: 'home' })
  } catch (error) {
    showConfirm.value = false
    errors.value = fieldErrors(error)
    message.value = errorMessage(error, '保存失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<template>
  <main class="home-page review-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'home' }">← 返回首页</RouterLink>
      <p class="brand">HealthTrace</p>
    </header>

    <p v-if="loading" class="field-hint" role="status">加载中…</p>
    <p v-else-if="loadError && !draft" class="notice notice-error" role="alert">{{ loadError }}</p>

    <template v-else-if="draft">
      <h1 class="review-title">{{ draft.report.title || '核对检查报告' }}</h1>

      <div class="review-layout">
        <PhotoViewer class="review-viewer" :photos="draft.photos" />

        <section class="home-card review-panel" aria-labelledby="review-panel-title">
          <h2 id="review-panel-title" class="visually-hidden">识别结果</h2>

          <div v-if="recognizing" class="review-status" role="status" aria-live="polite">
            <span class="spinner" aria-hidden="true"></span>
            <div>
              <p>正在识别，通常 30 秒内完成</p>
              <p v-if="retryHint" class="field-hint">{{ retryHint }}</p>
              <p v-if="pollTimedOut" class="field-hint">
                识别时间比预期长。
                <button type="button" class="text-link" @click="restartPoll">刷新状态</button>
              </p>
            </div>
          </div>

          <div v-else-if="failed" class="review-status review-failed" role="alert">
            <p>{{ failureText }}</p>
            <div class="review-actions">
              <button type="button" class="btn-primary" :disabled="actionBusy" @click="onRetryOcr">重新识别</button>
              <button type="button" class="btn-secondary" :disabled="actionBusy" @click="onManualEntry">
                手动录入
              </button>
            </div>
          </div>

          <div v-else-if="status === 'UPLOADING'" class="review-status" role="note">
            <p>这份报告还没有开始识别。</p>
            <div class="review-actions">
              <button type="button" class="btn-secondary" :disabled="actionBusy" @click="onManualEntry">
                手动录入
              </button>
            </div>
          </div>

          <template v-if="editable || confirmed">
            <p v-if="confirmed" class="notice notice-success" role="status">这份报告已确认保存。</p>

            <div class="field">
              <label for="review-date">检查日期（必填）</label>
              <input
                id="review-date"
                v-model="examinationDate"
                type="date"
                required
                :max="today"
                :disabled="!editable || submitting"
                :aria-invalid="errors.examinationDate ? 'true' : undefined"
                :aria-describedby="errors.examinationDate ? 'review-date-error' : undefined"
              />
              <p v-if="errors.examinationDate" id="review-date-error" class="field-error">
                {{ errors.examinationDate }}
              </p>
            </div>

            <ObservationTable
              :rows="rows"
              :errors="errors"
              :disabled="!editable || submitting"
              @edit="onEdit"
              @remove="onRemove"
              @add="onAdd"
            />
            <p v-if="errors.observations" class="field-error">{{ errors.observations }}</p>

            <RawTextPanel v-if="draft.rawText" :text="draft.rawText" />
          </template>

          <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
        </section>
      </div>

      <div v-if="editable" class="review-bar">
        <button type="button" class="btn-primary review-submit" :disabled="submitting" @click="onRequestConfirm">
          确认并保存
        </button>
      </div>

      <ConfirmDialog
        v-if="showConfirm"
        :count="rows.length"
        :examination-date="examinationDate"
        :submitting="submitting"
        @confirm="onConfirm"
        @cancel="showConfirm = false"
      />
    </template>
  </main>
</template>
