import { api } from './index'
import type { ObservationInput } from './ocr'

/** 正式报告修改（FR-014）：携带版本号做乐观锁，原图和 OCR 原文不会被改写 */
export interface ReviseCommand {
  version: number
  examinationDate: string
  reason?: string | null
  observations: ObservationInput[]
}

export interface ReviseResult {
  reportId: number
  version: number
  examinationDate: string
  revisionId: number
  changedCount: number
}

export type RevisionChangeType = 'ADDED' | 'UPDATED' | 'REMOVED'

/** 修改记录中某一项指标的快照 */
export interface ObservationSnapshot {
  originalName: string
  originalValue: string
  unitText: string | null
  referenceText: string | null
}

export interface RevisionChange {
  type: RevisionChangeType
  before: ObservationSnapshot | null
  after: ObservationSnapshot | null
}

export interface RevisionView {
  id: number
  fromVersion: number
  toVersion: number
  reason: string | null
  /** UTC 时间（无时区后缀） */
  createdAt: string
  examinationDate: { before: string | null; after: string | null } | null
  changes: RevisionChange[]
}

export const revisionApi = {
  /** PATCH /api/v1/reports/{id}：修改已确认报告，生成一条修改记录 */
  revise: (reportId: number, command: ReviseCommand) =>
    api.patch<ReviseResult>(`/reports/${reportId}`, command).then((res) => res.data),

  /** GET /api/v1/reports/{id}/revisions：修改记录，按时间倒序展示 */
  list: (reportId: number) =>
    api.get<RevisionView[]>(`/reports/${reportId}/revisions`).then((res) => res.data),
}
