import { api } from './index'
import type { AbnormalFlag, ResultKind } from './ocr'
import type { TrendChange } from './trend'

/** 对应后端 ComparisonViews.RowStatus */
export type ComparisonRowStatus = 'COMPARABLE' | 'NOT_COMPARABLE' | 'AMBIGUOUS' | 'BASE_ONLY' | 'TARGET_ONLY'

export interface ReportBrief {
  id: number
  title: string | null
  examinationDate: string | null
}

export interface ObservationCell {
  observationId: number
  originalValue: string
  numericValue: number | null
  resultKind: ResultKind
  unitText: string | null
  specimenText: string | null
  methodText: string | null
  referenceText: string | null
  abnormalFlag: AbnormalFlag | null
}

export interface ComparisonRow {
  key: string
  name: string
  standard: boolean
  status: ComparisonRowStatus
  base: ObservationCell | null
  target: ObservationCell | null
  /** 仅 COMPARABLE 且两侧都是精确数值时给出 */
  change: TrendChange | null
}

export interface ComparisonView {
  base: ReportBrief
  target: ReportBrief
  rows: ComparisonRow[]
}

export const compareApi = {
  /** GET /api/v1/report-comparisons：base 为“上次”，target 为“此次”；任一报告不属于本人或未确认时返回 404 */
  compare: (baseReportId: number, targetReportId: number) =>
    api
      .get<ComparisonView>('/report-comparisons', { params: { baseReportId, targetReportId } })
      .then((res) => res.data),
}
