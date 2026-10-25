import { api } from './index'
import type { PhotoView, ProcessingStatus } from './report'

/** OCR 任务状态（docs/19 OCR_JOB.status） */
export type OcrJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type ResultKind = 'EXACT' | 'LESS_THAN' | 'GREATER_THAN' | 'QUALITATIVE' | 'UNKNOWN'
export type AbnormalFlag = 'HIGH' | 'LOW' | 'NORMAL' | 'UNKNOWN'

export interface OcrJobView {
  id: number
  reportId: number
  status: OcrJobStatus
  attemptCount: number
  maxAttempts: number
  errorCode: string | null
  nextRetryAt: string | null
  createdAt: string
  finishedAt: string | null
}

export interface DraftReport {
  id: number
  profileId: number
  title: string | null
  examinationDate: string | null
  processingStatus: ProcessingStatus
  version: number
}

export interface ObservationView {
  id: number
  originalName: string
  originalValue: string
  unitText: string | null
  referenceText: string | null
  resultKind: ResultKind
  abnormalFlag: AbnormalFlag | null
  /** OCR 实际返回的置信度（0~1）；服务商未提供或已人工修改时为 null，不伪造 */
  confidence: number | null
  sourcePhotoId: number | null
  rowOrder: number
}

export interface DraftView {
  report: DraftReport
  photos: PhotoView[]
  ocrJob: OcrJobView | null
  /** 解密后的 OCR 原始文本，仅识别成功时返回 */
  rawText: string | null
  observations: ObservationView[]
}

export interface ObservationInput {
  id?: number
  originalName: string
  originalValue: string
  unitText?: string | null
  referenceText?: string | null
  sourcePhotoId?: number | null
}

export interface ConfirmCommand {
  version: number
  examinationDate: string
  observations: ObservationInput[]
}

export interface ConfirmResult {
  reportId: number
  processingStatus: ProcessingStatus
  version: number
  examinationDate: string
  observationCount: number
}

export const ocrApi = {
  /** POST /api/v1/reports/{id}/ocr-jobs?ocrConsent=true：用户明确同意后才提交第三方识别（NFR-002） */
  submit: (reportId: number) =>
    api
      .post<OcrJobView>(`/reports/${reportId}/ocr-jobs`, null, { params: { ocrConsent: true } })
      .then((res) => res.data),

  /** GET /api/v1/reports/{id}/ocr-jobs/latest：轮询识别状态 */
  latestJob: (reportId: number) =>
    api.get<OcrJobView>(`/reports/${reportId}/ocr-jobs/latest`).then((res) => res.data),

  /** POST /api/v1/reports/{id}/manual-entry：拒绝 OCR 或识别失败时转手动录入 */
  manualEntry: (reportId: number) =>
    api.post<DraftReport>(`/reports/${reportId}/manual-entry`).then((res) => res.data),

  /** GET /api/v1/reports/{id}/draft：原图信息、原始文本与草稿指标 */
  draft: (reportId: number) => api.get<DraftView>(`/reports/${reportId}/draft`).then((res) => res.data),

  /** POST /api/v1/reports/{id}/confirmation：整页确认，携带版本号做乐观锁 */
  confirm: (reportId: number, command: ConfirmCommand) =>
    api.post<ConfirmResult>(`/reports/${reportId}/confirmation`, command).then((res) => res.data),
}

/** OCR 归一化错误码 → 用户可读提示（不含病历内容） */
export function ocrErrorText(code: string | null | undefined): string {
  switch (code) {
    case 'OCR_UNRECOGNIZABLE':
      return '未能从图片中识别出检验项目，可以重新拍摄或手动录入。'
    case 'OCR_AUTH_FAILED':
      return 'OCR 服务配置异常，请稍后再试或手动录入。'
    case 'OCR_SOURCE_UNAVAILABLE':
      return '读取原图失败，请稍后重新识别。'
    case 'OCR_TIMEOUT':
      return '识别超时，请重新识别或手动录入。'
    default:
      return '识别暂时不可用，请重新识别或手动录入。'
  }
}
