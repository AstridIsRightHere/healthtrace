import { api } from './index'
import type { AbnormalFlag, ResultKind } from './ocr'

/** 时间区间（与后端 TrendRange 一致） */
export type TrendRangeKey = '7d' | '30d' | '1y' | '5y' | 'custom'

/** 有正式数据的指标；groupCount > 1 表示存在不同单位/标本/方法的结果 */
export interface MetricSummary {
  code: string
  standardName: string
  standardUnit: string
  count: number
  latestDate: string | null
  groupCount: number
  /** false：暂未纳入标准字典、按报告原名汇总的项目（code 以 RAW: 开头） */
  standard: boolean
}

export interface MetricInfo {
  code: string
  standardName: string
  standardUnit: string
  comparabilityNote: string | null
}

export interface DateRange {
  from: string
  to: string
}

/** 与同组最近此前可比点的增减；note 为 PREVIOUS_ZERO / SAME_DAY_AMBIGUOUS 时不给百分比 */
export interface TrendChange {
  absolute: number | null
  percent: number | null
  note: 'PREVIOUS_ZERO' | 'SAME_DAY_AMBIGUOUS' | null
}

export interface TrendPoint {
  observationId: number
  reportId: number
  examinationDate: string
  originalValue: string
  numericValue: number
  referenceText: string | null
  referenceLow: number | null
  referenceHigh: number | null
  abnormalFlag: AbnormalFlag | null
  change: TrendChange | null
}

/** 非精确结果：只进列表，不画线 */
export interface NonNumericPoint {
  observationId: number
  reportId: number
  examinationDate: string
  originalValue: string
  resultKind: ResultKind
  referenceText: string | null
  abnormalFlag: AbnormalFlag | null
}

export interface TrendGroup {
  groupKey: string
  unitText: string | null
  specimenText: string | null
  methodText: string | null
  matchesStandardUnit: boolean
  points: TrendPoint[]
  nonNumeric: NonNumericPoint[]
}

export interface TrendView {
  metric: MetricInfo
  range: DateRange
  groups: TrendGroup[]
  unitWarning: boolean
}

export interface TrendQuery {
  metricCode: string
  range: TrendRangeKey
  from?: string
  to?: string
}

/** 对应后端 metric-summaries 单项的最新正式数值结果 */
export interface LatestObservation {
  observationId: number
  reportId: number
  examinationDate: string
  originalValue: string
  numericValue: number | null
  unitText: string | null
  abnormalFlag: AbnormalFlag | null
}

/** 对应后端 metric-summaries 单项：最新结果及与同组上一次可比结果的变化 */
export interface MetricLatestSummary {
  metricCode: string
  standardName: string
  standardUnit: string
  latest: LatestObservation | null
  change: TrendChange | null
}

export interface MetricSummariesView {
  items: MetricLatestSummary[]
}

export const trendApi = {
  /** GET /api/v1/patient-profiles/{profileId}/metric-summaries?codes=A,B：首页关注卡片批量最新值（一次请求） */
  summaries: (profileId: number, codes: string[]) =>
    api
      .get<MetricSummariesView>(`/patient-profiles/${profileId}/metric-summaries`, {
        params: { codes: codes.join(',') },
      })
      .then((res) => res.data),

  /** GET /api/v1/patient-profiles/{profileId}/metrics：有正式数据的指标 */
  metrics: (profileId: number) =>
    api.get<MetricSummary[]>(`/patient-profiles/${profileId}/metrics`).then((res) => res.data),

  /** GET /api/v1/patient-profiles/{profileId}/trends：按可比组返回区间内正式结果 */
  trend: (profileId: number, query: TrendQuery) =>
    api
      .get<TrendView>(`/patient-profiles/${profileId}/trends`, {
        params: {
          metricCode: query.metricCode,
          range: query.range,
          ...(query.range === 'custom' ? { from: query.from, to: query.to } : {}),
        },
      })
      .then((res) => res.data),
}
