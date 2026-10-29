import { api } from './index'
import type { ObservationInput } from './ocr'

/**
 * 已确认报告的指标级修订（FR-014）：每次提交一组新增/修改/删除操作，携带版本号做乐观锁。
 * 原图和 OCR 原文不会被改写；被修改/删除的结果行只标记为 SUPERSEDED/RETRACTED，不会物理覆盖。
 */
export type RevisionOperationType = 'ADD' | 'UPDATE' | 'DELETE'

export interface RevisionOperation {
  op: RevisionOperationType
  /** UPDATE / DELETE：当前正式结果行 id */
  observationId?: number | null
  /** ADD / UPDATE：修改后的完整字段 */
  observation?: ObservationInput | null
}

export interface CreateRevisionCommand {
  version: number
  reason?: string | null
  /** 为空表示不修改检查日期 */
  examinationDate?: string | null
  operations: RevisionOperation[]
}

export interface ReviseResult {
  reportId: number
  version: number
  examinationDate: string
  revisionId: number
  changeCount: number
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
  /** 变更前的行（ADDED 为空；早期记录为空） */
  observationId?: number | null
  /** 变更后的新行（REMOVED 为空；早期记录为空） */
  newObservationId?: number | null
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

export type ObservationVersionStatus = 'DRAFT' | 'FORMAL' | 'DELETING' | 'SUPERSEDED' | 'RETRACTED'

/** 单个指标版本链中的一个版本 */
export interface ObservationVersionView {
  id: number
  supersedesId: number | null
  status: ObservationVersionStatus
  originalName: string
  originalValue: string
  unitText: string | null
  referenceText: string | null
  examinationDate: string | null
  sourcePhotoId: number | null
  createdRevisionId: number | null
  retiredRevisionId: number | null
  createdAt: string
  retiredAt: string | null
}

export const revisionApi = {
  /** POST /api/v1/reports/{id}/revisions：提交一次修订，201 返回新版本号 */
  create: (reportId: number, command: CreateRevisionCommand) =>
    api.post<ReviseResult>(`/reports/${reportId}/revisions`, command).then((res) => res.data),

  /** GET /api/v1/reports/{id}/revisions：修改记录，按时间倒序展示 */
  list: (reportId: number) =>
    api.get<RevisionView[]>(`/reports/${reportId}/revisions`).then((res) => res.data),

  /** GET /api/v1/reports/{id}/observations/{observationId}/history：单项指标版本链，最新在前 */
  history: (reportId: number, observationId: number) =>
    api
      .get<ObservationVersionView[]>(`/reports/${reportId}/observations/${observationId}/history`)
      .then((res) => res.data),
}
