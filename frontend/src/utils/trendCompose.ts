// 多指标组合趋势的分图逻辑（纯函数）。
// DEC-015：只有“单位写法 + 标本 + 方法”完全一致（仅去除空白）的系列才画在同一张图里，
// 不做任何单位换算；未标准化（RAW:）指标不与标准指标同图，避免把未经字典确认的项目混入比较。
import type { MetricInfo, NonNumericPoint, TrendGroup, TrendPoint, TrendView } from '../api/trend'
import { dateToTime } from './trendOption'

/** 一个指标在某个可比组内的数据 */
export interface SeriesSlice {
  /** 指标编码 + 组键，页面内唯一 */
  id: string
  metric: MetricInfo
  standard: boolean
  group: TrendGroup
  points: TrendPoint[]
  nonNumeric: NonNumericPoint[]
}

/** 一张图：内部所有系列可比（同单位、同标本、同方法、同为标准/未标准化） */
export interface ChartPanel {
  key: string
  unitText: string | null
  specimenText: string | null
  methodText: string | null
  standard: boolean
  series: SeriesSlice[]
}

export interface ComposedTrends {
  /** 至少 2 个精确数值点的系列，按可比条件分图 */
  panels: ChartPanel[]
  /** 只有 1 个精确数值点的系列：渲染为单点大卡片 */
  singles: SeriesSlice[]
  /** 区间内没有精确数值点（可能只有非精确结果）的系列 */
  noNumeric: SeriesSlice[]
  /** 图表因可比条件不同被拆成多张（或同一指标存在多个可比组） */
  splitByComparability: boolean
}

/** 可比条件文本规范化：只去除空白，不改大小写、不做同义词或单位换算 */
export function normalizeCondition(value: string | null | undefined): string {
  return (value ?? '').replace(/\s+/g, '')
}

export function isRawCode(code: string): boolean {
  return code.startsWith('RAW:')
}

export function comparabilityKey(group: Pick<TrendGroup, 'unitText' | 'specimenText' | 'methodText'>, standard: boolean): string {
  return [
    standard ? 'STD' : 'RAW',
    normalizeCondition(group.unitText),
    normalizeCondition(group.specimenText),
    normalizeCondition(group.methodText),
  ].join('|')
}

function sortedPoints(points: TrendPoint[]): TrendPoint[] {
  return [...points].sort(
    (a, b) => dateToTime(a.examinationDate) - dateToTime(b.examinationDate) || a.observationId - b.observationId,
  )
}

/** 把多个指标的趋势结果拆成可比系列并分图。输入顺序即用户选择顺序，输出保持该顺序。 */
export function composeTrends(trends: TrendView[]): ComposedTrends {
  const panels = new Map<string, ChartPanel>()
  const singles: SeriesSlice[] = []
  const noNumeric: SeriesSlice[] = []
  let anyMultiGroup = false

  for (const trend of trends) {
    const standard = !isRawCode(trend.metric.code)
    if (trend.groups.length > 1) anyMultiGroup = true
    for (const group of trend.groups) {
      const slice: SeriesSlice = {
        id: `${trend.metric.code}#${group.groupKey}`,
        metric: trend.metric,
        standard,
        group,
        points: sortedPoints(group.points),
        nonNumeric: group.nonNumeric,
      }
      if (slice.points.length === 0) {
        noNumeric.push(slice)
      } else if (slice.points.length === 1) {
        singles.push(slice)
      } else {
        const key = comparabilityKey(group, standard)
        let panel = panels.get(key)
        if (!panel) {
          panel = {
            key,
            unitText: group.unitText,
            specimenText: group.specimenText,
            methodText: group.methodText,
            standard,
            series: [],
          }
          panels.set(key, panel)
        }
        panel.series.push(slice)
      }
    }
  }

  const panelList = [...panels.values()]
  return {
    panels: panelList,
    singles,
    noNumeric,
    splitByComparability: panelList.length > 1 || anyMultiGroup,
  }
}

/** 可比条件的展示文字，如“mmol/L · 血清 · 酶法” */
export function conditionLabel(group: Pick<TrendGroup, 'unitText' | 'specimenText' | 'methodText'>): string {
  const parts = [group.unitText || '单位未标注']
  if (group.specimenText) parts.push(group.specimenText)
  if (group.methodText) parts.push(group.methodText)
  return parts.join(' · ')
}

/** URL 参数 metric=A,B,C → 编码列表（去重、去空、限制数量） */
export function parseMetricCodes(raw: unknown, max: number): string[] {
  const values = Array.isArray(raw) ? raw : [raw]
  const codes: string[] = []
  for (const v of values) {
    if (typeof v !== 'string') continue
    for (const part of v.split(',')) {
      const code = part.trim()
      if (code && !codes.includes(code)) codes.push(code)
    }
  }
  return codes.slice(0, max)
}
