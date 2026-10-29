import { api, type ListPage } from './index'

/** 报告处理状态（docs/19 REPORT.processing_status） */
export type ProcessingStatus =
  | 'UPLOADING'
  | 'RECOGNIZING'
  | 'PENDING_CONFIRMATION'
  | 'CONFIRMED'
  | 'FAILED'
export type ReportType = 'BLOOD_ROUTINE' | 'LIVER' | 'KIDNEY' | 'OTHER' | 'UNKNOWN'
export type DuplicateDecision = 'ASK' | 'KEEP'
export type PhotoVariant = 'preview' | 'original'

/** 对应后端 ReportView；时间为 UTC */
export interface ReportView {
  id: number
  profileId: number
  title: string | null
  reportType: ReportType
  examinationDate: string | null
  processingStatus: ProcessingStatus
  createdAt: string
}

export interface PhotoView {
  id: number
  reportId: number
  pageOrder: number
  mediaType: string
  byteSize: number
  hasPreview: boolean
}

/** 上传结果：疑似重复时不落盘，返回同档已有报告编号，由用户决定是否仍然上传 */
export interface PhotoUploadResult {
  result: 'UPLOADED' | 'DUPLICATE_SUSPECTED'
  photo: PhotoView | null
  existingReportIds: number[] | null
}

export interface ReportListItem {
  id: number
  title: string | null
  reportType: ReportType
  examinationDate: string | null
  processingStatus: ProcessingStatus
  createdAt: string
  photoCount: number
  coverPhotoId: number | null
  coverHasPreview: boolean
  /** 最近一次 OCR 任务的归一化错误码（无任务或成功时为 null） */
  lastOcrErrorCode: string | null
  /** 列表移除时间：仅回收站（status=REMOVED）返回，其他列表为 null（FR-016） */
  removedAt?: string | null
}

/** 已确认报告按种类聚合的一组；reports 按检查日期、id 倒序 */
export interface ReportGroup {
  key: string
  label: string
  count: number
  latestDate: string | null
  reports: ReportListItem[]
}

export interface CreateReportPayload {
  title?: string
  /** yyyy-MM-dd，可空 */
  examinationDate?: string
}

export interface UploadPhotoPayload {
  original: File
  preview?: Blob | null
  pageOrder: number
  duplicateDecision?: DuplicateDecision
  onProgress?: (percent: number) => void
  signal?: AbortSignal
}

export const reportApi = {
  /** POST /api/v1/patient-profiles/{profileId}/reports：创建上传中的报告草稿（FR-004） */
  create: (profileId: number, payload: CreateReportPayload) =>
    api.post<ReportView>(`/patient-profiles/${profileId}/reports`, payload).then((res) => res.data),

  /** POST /api/v1/reports/{reportId}/photos：单文件上传，原件必传，预览图仅 JPEG/PNG（FR-005、DEC-008） */
  uploadPhoto: async (reportId: number, payload: UploadPhotoPayload): Promise<PhotoUploadResult> => {
    const form = new FormData()
    form.append('original', payload.original, payload.original.name)
    if (payload.preview) form.append('preview', payload.preview, 'preview.jpg')
    form.append('pageOrder', String(payload.pageOrder))
    form.append('duplicateDecision', payload.duplicateDecision ?? 'ASK')
    const res = await api.post<PhotoUploadResult>(`/reports/${reportId}/photos`, form, {
      signal: payload.signal,
      onUploadProgress: (event) => {
        if (payload.onProgress && event.total) {
          payload.onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
    return res.data
  },

  /** GET /api/v1/patient-profiles/{profileId}/reports?status=DRAFT&limit=n：待处理列表，total 为全部待处理数 */
  listDrafts: (profileId: number, limit?: number) =>
    api
      .get<ListPage<ReportListItem>>(`/patient-profiles/${profileId}/reports`, {
        params: limit ? { status: 'DRAFT', limit } : { status: 'DRAFT' },
      })
      .then((res) => res.data),

  /** GET /api/v1/patient-profiles/{profileId}/reports?status=CONFIRMED：已确认报告，按检查日期倒序 */
  listConfirmed: (profileId: number) =>
    api
      .get<ListPage<ReportListItem>>(`/patient-profiles/${profileId}/reports`, { params: { status: 'CONFIRMED' } })
      .then((res) => res.data.items),

  /** GET /api/v1/patient-profiles/{profileId}/report-groups：已确认报告按种类聚合（报告对比入口） */
  listConfirmedGroups: (profileId: number) =>
    api.get<ReportGroup[]>(`/patient-profiles/${profileId}/report-groups`).then((res) => res.data),

  /**
   * DELETE /api/v1/reports/{reportId}：彻底删除（FR-016），任何状态（含识别中、已确认）均可。
   * 原件、预览图、OCR 任务、检验结果、修改记录和就诊关联一并清除，不可恢复。
   */
  remove: (reportId: number) => api.delete<void>(`/reports/${reportId}`).then(() => undefined),

  /** POST /api/v1/reports/{reportId}/list-removals：列表移除（移入回收站），可恢复 */
  listRemove: (reportId: number) => api.post<void>(`/reports/${reportId}/list-removals`).then(() => undefined),

  /** DELETE /api/v1/reports/{reportId}/list-removals：从回收站恢复 */
  restore: (reportId: number) => api.delete<void>(`/reports/${reportId}/list-removals`).then(() => undefined),

  /** GET /api/v1/patient-profiles/{profileId}/reports?status=REMOVED：回收站，按移除时间倒序 */
  listRemoved: (profileId: number) =>
    api
      .get<ListPage<ReportListItem>>(`/patient-profiles/${profileId}/reports`, { params: { status: 'REMOVED' } })
      .then((res) => res.data),

  /** 图片访问地址：同源会话 Cookie 鉴权，服务端返回 no-store 或 302 到短时签名 URL */
  photoUrl: (photoId: number, variant: PhotoVariant = 'preview') =>
    `/api/v1/photos/${photoId}/access?variant=${variant}`,
}
