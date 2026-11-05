<script setup lang="ts">
// 编辑报告标题与检查日期（弹窗）；保存走 PATCH /api/v1/reports/{id}，后端记审计。
import { nextTick, onMounted, reactive, ref } from 'vue'
import { errorMessage, fieldErrors } from '../../api'
import { reportApi, type ProcessingStatus, type ReportKind, type ReportView } from '../../api/report'
import {
  TITLE_MAX_LENGTH,
  metaUnchanged,
  toMetaPayload,
  validateReportMeta,
  type ReportMetaErrors,
} from '../../utils/reportMeta'

const props = defineProps<{
  reportId: number
  report: {
    title: string | null
    examinationDate: string | null
    processingStatus: ProcessingStatus
    reportKind: ReportKind
  }
}>()
const emit = defineEmits<{ saved: [view: ReportView]; cancel: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)
const today = new Date().toLocaleDateString('sv-SE')
const form = reactive({ title: props.report.title ?? '', examinationDate: props.report.examinationDate ?? '' })
const errors = ref<ReportMetaErrors>({})
const message = ref('')
const saving = ref(false)

onMounted(async () => {
  await nextTick()
  dialog.value?.showModal()
})

function cancel() {
  if (!saving.value) emit('cancel')
}

async function save() {
  if (saving.value) return
  message.value = ''
  errors.value = validateReportMeta(form, today, props.report)
  if (Object.keys(errors.value).length) return
  if (metaUnchanged(form, props.report)) {
    emit('cancel')
    return
  }
  saving.value = true
  try {
    emit('saved', await reportApi.updateMetadata(props.reportId, toMetaPayload(form)))
  } catch (error) {
    const fields = fieldErrors(error)
    errors.value = { title: fields.title, examinationDate: fields.examinationDate }
    if (!fields.title && !fields.examinationDate) message.value = errorMessage(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="dup-dialog"
    aria-labelledby="edit-report-title"
    data-testid="edit-report-dialog"
    @cancel.prevent="cancel"
  >
    <form novalidate @submit.prevent="save">
      <h2 id="edit-report-title">编辑报告信息</h2>
      <div class="field">
        <label for="edit-report-name">报告标题</label>
        <input
          id="edit-report-name"
          v-model="form.title"
          type="text"
          :maxlength="TITLE_MAX_LENGTH"
          placeholder="例如：血常规"
          :aria-invalid="!!errors.title"
          data-testid="edit-report-name"
        />
        <p v-if="errors.title" class="field-error" role="alert">{{ errors.title }}</p>
      </div>
      <div class="field">
        <label for="edit-report-date">检查日期</label>
        <input
          id="edit-report-date"
          v-model="form.examinationDate"
          type="date"
          :max="today"
          :aria-invalid="!!errors.examinationDate"
          data-testid="edit-report-date"
        />
        <p v-if="errors.examinationDate" class="field-error" role="alert">{{ errors.examinationDate }}</p>
        <p v-else class="field-hint">修改检查日期后，趋势图中该报告的数据点会一并移动。</p>
      </div>
      <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
      <div class="dup-actions">
        <button class="btn-secondary" type="button" :disabled="saving" @click="cancel">取消</button>
        <button class="btn-primary" type="submit" :disabled="saving" data-testid="edit-report-save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </form>
  </dialog>
</template>
