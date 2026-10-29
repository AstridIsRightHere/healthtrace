import { api, type ListPage } from './index'
import type { ProcessingStatus, ReportType } from './report'

/** 对应后端 VisitViews.LinkedReportView */
export interface LinkedReport {
  id: number
  title: string | null
  reportType: ReportType
  examinationDate: string | null
  processingStatus: ProcessingStatus
}

/** 对应后端 VisitViews.VisitView；visitDate 为 yyyy-MM-dd，时间戳为 UTC */
export interface Visit {
  id: number
  profileId: number
  visitDate: string
  hospitalText: string
  department: string | null
  note: string | null
  version: number
  createdAt: string
  updatedAt: string
  reports: LinkedReport[]
}

export interface VisitPayload {
  visitDate: string
  hospitalText: string
  department?: string | null
  note?: string | null
}

export const visitApi = {
  /** GET /api/v1/patient-profiles/{profileId}/visits?limit=n：就诊日期倒序（FR-013），total 为全部就诊数 */
  list: (profileId: number, limit?: number) =>
    api
      .get<ListPage<Visit>>(`/patient-profiles/${profileId}/visits`, { params: limit ? { limit } : {} })
      .then((res) => res.data),

  create: (profileId: number, payload: VisitPayload) =>
    api.post<Visit>(`/patient-profiles/${profileId}/visits`, payload).then((res) => res.data),

  update: (visitId: number, version: number, payload: VisitPayload) =>
    api.patch<Visit>(`/visits/${visitId}`, { ...payload, version }).then((res) => res.data),

  remove: (visitId: number) => api.delete<void>(`/visits/${visitId}`).then(() => undefined),

  linkReport: (visitId: number, reportId: number) =>
    api.put<Visit>(`/visits/${visitId}/reports/${reportId}`).then((res) => res.data),

  unlinkReport: (visitId: number, reportId: number) =>
    api.delete<Visit>(`/visits/${visitId}/reports/${reportId}`).then((res) => res.data),
}
