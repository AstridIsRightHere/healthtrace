/** 上传队列中单个文件的状态（FR-005：一文件一请求，逐个可重试） */
export type QueueStatus =
  | 'waiting' // 已选择，未开始
  | 'preparing' // 生成预览图中
  | 'uploading'
  | 'done'
  | 'duplicate' // 疑似重复，等待用户决定
  | 'skipped' // 用户选择跳过重复文件
  | 'failed'

export interface QueueItem {
  key: string
  file: File
  /** 本地缩略图（仅 JPEG/PNG），需在移除/卸载时 revoke */
  thumbUrl: string | null
  status: QueueStatus
  progress: number
  error: string
  existingReportIds: number[]
  /** 开始上传时锁定的页序，重试沿用 */
  pageOrder: number | null
}

export const ACCEPTED_TYPES = 'image/jpeg,image/png,image/heic,image/heif,.heic,.heif,application/pdf'
