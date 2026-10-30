<script setup lang="ts">
// 草稿核对页（FR-006/FR-007/FR-014/FR-015）：左侧原图对照，右侧可编辑指标表；整页确认 + 二次确认后才进入图表
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { errorMessage, fieldErrors, type ApiErrorBody } from '../api'
import { ocrApi, ocrErrorText, type DraftView } from '../api/ocr'
import PhotoViewer from '../components/review/PhotoViewer.vue'
import ObservationTable from '../components/review/ObservationTable.vue'
import RawTextPanel from '../components/review/RawTextPanel.vue'
import ConfirmDialog from '../components/review/ConfirmDialog.vue'
import type { EditableRow } from '../components/review/types'
import { revisionApi, type RevisionView } from '../api/revision'
import { reportApi } from '../api/report'
import DeleteReportDialog from '../components/home/DeleteReportDialog.vue'
import {
  REASON_MAX_LENGTH,
  buildRevisionCommand,
  formatChange,
  formatDateChange,
  formatRevisionTime,
  formatVersions,
  hasNoChange,
  sortRevisions,
} from '../utils/revision'

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
// 正式报告修改（FR-014）：进入编辑模式后复用草稿的表格与校验
const revising = ref(false)
const reason = ref('')
const revisions = ref<RevisionView[]>([])
const revisionsLoaded = ref(false)
const revisionsError = ref('')
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null
const tableEditable = computed(() => editable.value || (confirmed.value && revising.value))
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
    if (confirmed.value && !revisionsLoaded.value) void loadRevisions()
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

async function loadRevisions() {
  try {
    revisions.value = sortRevisions(await revisionApi.list(reportId))
    revisionsError.value = ''
  } catch (error) {
    revisionsError.value = errorMessage(error, '修改记录加载失败，请稍后刷新')
  } finally {
    revisionsLoaded.value = true
  }
}

function showToast(text: string) {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = text
  toastTimer = setTimeout(() => {
    toast.value = ''
    toastTimer = null
  }, 3000)
}

function onStartRevise() {
  message.value = ''
  errors.value = {}
  reason.value = ''
  revising.value = true
}

/** 取消修改：恢复为已保存的内容 */
function onCancelRevise() {
  if (submitting.value) return
  if (draft.value) apply(draft.value)
  revising.value = false
  reason.value = ''
  errors.value = {}
  message.value = ''
}

function onRequestRevise() {
  message.value = ''
  const view = draft.value
  if (!view) return
  if (!validate()) {
    message.value = '还有内容需要补充，请检查标红的字段。'
    return
  }
  if (reason.value.trim().length > REASON_MAX_LENGTH) {
    errors.value = { reason: `修改原因最多 ${REASON_MAX_LENGTH} 字` }
    return
  }
  if (hasNoChange(view.observations, view.report.examinationDate, rows.value, examinationDate.value)) {
    message.value = '没有需要保存的修改'
    return
  }
  showConfirm.value = true
}

function reviseErrorText(error: unknown): string {
  if (isAxiosError<ApiErrorBody>(error)) {
    const data = error.response?.data
    if (error.response?.status === 409 && data?.code === 'HT-3002') return '内容已被修改，请刷新后重试'
    if (error.response?.status === 400 && data?.fieldErrors?.some((f) => f.reasonCode === 'NO_CHANGE')) {
      return '没有需要保存的修改'
    }
  }
  return errorMessage(error, '保存修改失败，请稍后重试')
}

async function onRevise() {
  const view = draft.value
  if (!view || submitting.value) return
  submitting.value = true
  try {
    await revisionApi.create(
      reportId,
      buildRevisionCommand(
        view.report.version,
        view.observations,
        view.report.examinationDate,
        rows.value,
        examinationDate.value,
        reason.value,
      ),
    )
    showConfirm.value = false
    revising.value = false
    reason.value = ''
    errors.value = {}
    await load()
    await loadRevisions()
    showToast('已保存修改')
  } catch (error) {
    showConfirm.value = false
    const fields = fieldErrors(error)
    delete fields.observations
    errors.value = fields
    message.value = reviseErrorText(error)
  } finally {
    submitting.value = false
  }
}

// 彻底删除（FR-016）：任何状态均可删除（识别中的任务一并清除）；成功后回到首页
const showPurge = ref(false)
const purging = ref(false)
const purgeError = ref('')

function openPurge() {
  purgeError.value = ''
  showPurge.value = true
}

function cancelPurge() {
  if (!purging.value) showPurge.value = false
}

async function confirmPurge() {
  purging.value = true
  purgeError.value = ''
  try {
    await reportApi.remove(reportId)
    showPurge.value = false
    await router.replace({ name: 'home' })
  } catch (error) {
    purgeError.value = errorMessage(error, '删除失败，请稍后重试')
  } finally {
    purging.value = false
  }
}

// 列表移除（FR-016）：报告退出列表、趋势图和报告对比，可在回收站恢复或彻底删除
const removing = ref(false)

async function onListRemove() {
  if (removing.value) return
  removing.value = true
  purgeError.value = ''
  try {
    await reportApi.listRemove(reportId)
    await router.replace({ name: 'home' })
  } catch (error) {
    purgeError.value = errorMessage(error, '移除失败，请稍后重试')
  } finally {
    removing.value = false
  }
}

function onDialogConfirm() {
  if (revising.value) void onRevise()
  else void onConfirm()
}

onMounted(load)
onBeforeUnmount(() => {
  stopPoll()
  if (toastTimer) clearTimeout(toastTimer)
})
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
            <div v-if="confirmed" class="revise-head">
              <p class="notice notice-success" role="status">
                {{ revising ? '正在修改这份报告，保存后会生成一条修改记录。' : '这份报告已确认保存。' }}
              </p>
              <button
                v-if="!revising"
                type="button"
                class="btn-ghost"
                data-testid="report-edit"
                @click="onStartRevise"
              >
                修改结果
              </button>
            </div>

            <div class="field">
              <label for="review-date">检查日期（必填）</label>
              <input
                id="review-date"
                v-model="examinationDate"
                type="date"
                required
                :max="today"
                :disabled="!tableEditable || submitting"
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
              :disabled="!tableEditable || submitting"
              @edit="onEdit"
              @remove="onRemove"
              @add="onAdd"
            />
            <p v-if="errors.observations" class="field-error">{{ errors.observations }}</p>

            <template v-if="confirmed && revising">
              <div class="field">
                <label for="revise-reason">修改原因（选填）</label>
                <textarea
                  id="revise-reason"
                  v-model="reason"
                  rows="3"
                  :maxlength="REASON_MAX_LENGTH"
                  placeholder="如：识别时数值看错"
                  :disabled="submitting"
                  :aria-invalid="errors.reason ? 'true' : undefined"
                  aria-describedby="revise-reason-hint"
                  data-testid="report-edit-reason"
                ></textarea>
                <p id="revise-reason-hint" class="field-hint">{{ reason.length }}/{{ REASON_MAX_LENGTH }}</p>
                <p v-if="errors.reason" class="field-error">{{ errors.reason }}</p>
              </div>
              <div class="review-actions">
                <button
                  type="button"
                  class="btn-primary"
                  :disabled="submitting"
                  data-testid="report-edit-save"
                  @click="onRequestRevise"
                >
                  保存修改
                </button>
                <button
                  type="button"
                  class="btn-ghost"
                  :disabled="submitting"
                  data-testid="report-edit-cancel"
                  @click="onCancelRevise"
                >
                  取消
                </button>
              </div>
            </template>

            <RawTextPanel v-if="draft.rawText" :text="draft.rawText" />
          </template>

          <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
        </section>
      </div>

      <section v-if="confirmed" class="home-card revision-section" aria-labelledby="revision-title">
        <h2 id="revision-title">修改记录</h2>
        <p v-if="!revisionsLoaded" class="field-hint" role="status">加载中…</p>
        <p v-else-if="revisionsError" class="notice notice-error" role="alert">{{ revisionsError }}</p>
        <p v-else-if="revisions.length === 0" class="field-hint">暂无修改记录</p>
        <ol v-else class="revision-list" data-testid="revision-list">
          <li v-for="item in revisions" :key="item.id" class="revision-item" data-testid="revision-item">
            <p class="revision-meta">
              <time :datetime="item.createdAt">{{ formatRevisionTime(item.createdAt) }}</time>
              <span>{{ formatVersions(item) }}</span>
            </p>
            <p class="revision-reason">{{ item.reason?.trim() || '未填写原因' }}</p>
            <ul class="revision-changes">
              <li v-if="formatDateChange(item.examinationDate)">{{ formatDateChange(item.examinationDate) }}</li>
              <li v-for="(change, i) in item.changes" :key="i">{{ formatChange(change) }}</li>
            </ul>
          </li>
        </ol>
      </section>

      <section
        v-if="!revising"
        class="home-card danger-zone"
        aria-labelledby="purge-title"
        data-testid="report-danger-zone"
      >
        <h2 id="purge-title">移除与删除</h2>
        <p class="field-hint">
          移入回收站：报告不再出现在列表、趋势图和报告对比中，可在回收站恢复。彻底删除：原件和所有分析数据将被永久移除，无法恢复。
        </p>
        <p v-if="purgeError && !showPurge" class="notice notice-error" role="alert" data-testid="list-remove-error">
          {{ purgeError }}
        </p>
        <button
          type="button"
          class="btn-secondary btn-small"
          :disabled="removing || purging"
          data-testid="list-remove"
          @click="onListRemove"
        >
          {{ removing ? '移除中…' : '移入回收站' }}
        </button>
        <button
          type="button"
          class="btn-danger-outline btn-small"
          :disabled="purging"
          data-testid="purge-open"
          @click="openPurge"
        >
          彻底删除这份报告
        </button>
      </section>

      <DeleteReportDialog
        v-if="showPurge"
        :report-title="draft.report.title || '这份报告'"
        :deleting="purging"
        :error="purgeError"
        @confirm="confirmPurge"
        @cancel="cancelPurge"
      />

      <Transition name="toast">
        <p v-if="toast" class="toast notice-success" role="status" data-testid="report-edit-toast">{{ toast }}</p>
      </Transition>

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
        :title="revising ? '保存对这份报告的修改？' : undefined"
        :description="revising ? '修改会生成一条修改记录，原图和 OCR 原文不会被改写。' : undefined"
        :confirm-text="revising ? '保存修改' : undefined"
        @confirm="onDialogConfirm"
        @cancel="showConfirm = false"
      />
    </template>
  </main>
</template>
