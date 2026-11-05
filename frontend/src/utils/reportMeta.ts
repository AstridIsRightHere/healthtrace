// 报告元数据编辑（标题、检查日期）的前端校验；规则与后端 ReportUploadService 保持一致，最终以后端为准。
import type { ProcessingStatus, ReportKind } from '../api/report'

export const TITLE_MAX_LENGTH = 120

export interface ReportMetaForm {
  title: string
  examinationDate: string
}

export type ReportMetaErrors = Partial<Record<keyof ReportMetaForm, string>>

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

export function validateReportMeta(
  form: ReportMetaForm,
  today: string,
  report: { processingStatus: ProcessingStatus; reportKind: ReportKind },
): ReportMetaErrors {
  const errors: ReportMetaErrors = {}
  const title = form.title.trim()
  if ([...title].length > TITLE_MAX_LENGTH) errors.title = `标题不能超过 ${TITLE_MAX_LENGTH} 个字符`
  const date = form.examinationDate
  if (date) {
    if (!ISO_DATE.test(date)) errors.examinationDate = '请填写有效的日期'
    else if (date > today) errors.examinationDate = '检查日期不能晚于今天'
  } else if (report.processingStatus === 'CONFIRMED' && report.reportKind === 'STRUCTURED') {
    errors.examinationDate = '已确认的检验报告必须填写检查日期'
  }
  return errors
}

/** 空白标题、空日期提交为 null（后端按原样写入） */
export function toMetaPayload(form: ReportMetaForm): { title: string | null; examinationDate: string | null } {
  const title = form.title.trim()
  return { title: title || null, examinationDate: form.examinationDate || null }
}

export function metaUnchanged(
  form: ReportMetaForm,
  current: { title: string | null; examinationDate: string | null },
): boolean {
  const next = toMetaPayload(form)
  return next.title === (current.title?.trim() || null) && next.examinationDate === (current.examinationDate || null)
}
