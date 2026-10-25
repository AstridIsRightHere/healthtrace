<script setup lang="ts">
// 首页待处理报告（status=DRAFT：未确认且正常可见）
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { errorMessage } from '../../api'
import { ocrErrorText } from '../../api/ocr'
import { reportApi, type ProcessingStatus, type ReportListItem } from '../../api/report'
import DeleteReportDialog from './DeleteReportDialog.vue'

const props = defineProps<{ profileId: number }>()

const reports = ref<ReportListItem[]>([])
const loading = ref(true)
const message = ref('')

const statusText: Record<ProcessingStatus, string> = {
  UPLOADING: '上传中',
  RECOGNIZING: '识别中',
  PENDING_CONFIRMATION: '待确认',
  CONFIRMED: '已确认',
  FAILED: '处理失败',
}

function actionText(status: ProcessingStatus): string {
  if (status === 'PENDING_CONFIRMATION') return '去核对'
  if (status === 'RECOGNIZING') return '查看进度'
  if (status === 'FAILED') return '处理'
  return '识别 / 录入'
}

function title(report: ReportListItem): string {
  return report.title || (report.examinationDate ? `${report.examinationDate} 的检查报告` : '未命名报告')
}

function uploadedAt(iso: string): string {
  // 后端时间为 UTC（无时区后缀），按 UTC 解析再本地显示
  const date = new Date(/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : `${iso}Z`)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

// 没有任何上传完成文件的报告无法识别或录入（后端 409），只提供删除
function isEmpty(report: ReportListItem): boolean {
  return report.photoCount === 0 && report.processingStatus !== 'RECOGNIZING'
}

const pendingDelete = ref<ReportListItem | null>(null)
const deleting = ref(false)
const deleteError = ref('')

function askDelete(report: ReportListItem) {
  deleteError.value = ''
  pendingDelete.value = report
}

function cancelDelete() {
  if (!deleting.value) pendingDelete.value = null
}

async function confirmDelete() {
  const target = pendingDelete.value
  if (!target) return
  deleting.value = true
  deleteError.value = ''
  try {
    await reportApi.remove(target.id)
    reports.value = reports.value.filter((r) => r.id !== target.id)
    pendingDelete.value = null
  } catch (error) {
    deleteError.value = errorMessage(error, '删除失败，请稍后重试')
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  try {
    reports.value = await reportApi.listDrafts(props.profileId)
  } catch (error) {
    message.value = errorMessage(error, '待处理报告加载失败，请稍后刷新')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="home-card pending-reports" aria-labelledby="pending-title">
    <h2 id="pending-title">待处理报告</h2>
    <p v-if="loading" class="field-hint" role="status">加载中…</p>
    <p v-else-if="message" class="notice notice-error" role="alert">{{ message }}</p>
    <p v-else-if="reports.length === 0" class="field-hint">还没有待处理的报告，点击右下角“上传报告”开始。</p>
    <ul v-else class="pending-list">
      <li v-for="report in reports" :key="report.id" class="pending-item">
        <img
          v-if="report.coverPhotoId && report.coverHasPreview"
          class="queue-thumb"
          :src="reportApi.photoUrl(report.coverPhotoId, 'preview')"
          alt=""
          loading="lazy"
        />
        <span v-else class="queue-thumb queue-thumb-placeholder" aria-hidden="true">报告</span>
        <div class="queue-meta">
          <p class="queue-name">{{ title(report) }}</p>
          <p class="queue-sub">
            {{ report.photoCount }} 个文件 · {{ statusText[report.processingStatus] }} · {{ uploadedAt(report.createdAt) }}
          </p>
          <p v-if="report.processingStatus === 'FAILED'" class="field-error">
            {{ ocrErrorText(report.lastOcrErrorCode) }}
          </p>
        </div>
        <button
          v-if="isEmpty(report)"
          type="button"
          class="btn-ghost btn-small"
          :aria-label="`删除报告：${title(report)}`"
          @click="askDelete(report)"
        >
          删除
        </button>
        <RouterLink
          v-else
          class="btn-secondary btn-small"
          :to="{ name: 'report-review', params: { reportId: String(report.id) } }"
        >
          {{ actionText(report.processingStatus) }}
        </RouterLink>
      </li>
    </ul>

    <DeleteReportDialog
      v-if="pendingDelete"
      :key="pendingDelete.id"
      :report-title="title(pendingDelete)"
      :deleting="deleting"
      :error="deleteError"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />
  </section>
</template>
