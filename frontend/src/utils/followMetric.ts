// 首页关注指标卡片的纯逻辑：从趋势接口结果提取最新值和变化方向
import type { AbnormalFlag } from '../api/ocr'
import type { MetricLatestSummary, TrendChange, TrendView } from '../api/trend'

export type TrendDirection = 'up' | 'down' | 'flat' | 'none'

export interface FollowCard {
  code: string
  name: string
  /** 最新一次精确结果的原始值；无记录为 null */
  value: string | null
  unit: string | null
  date: string | null
  direction: TrendDirection
  abnormalFlag: AbnormalFlag | null
}

/** 与同组上一次可比结果的方向；无此前可比点或同日歧义时不给方向 */
export function trendDirection(change: TrendChange | null): TrendDirection {
  if (!change || change.absolute === null || change.note === 'SAME_DAY_AMBIGUOUS') return 'none'
  if (change.absolute > 0) return 'up'
  if (change.absolute < 0) return 'down'
  return 'flat'
}

export const DIRECTION_ARROW: Record<TrendDirection, string> = { up: '↑', down: '↓', flat: '→', none: '' }
export const DIRECTION_TEXT: Record<TrendDirection, string> = {
  up: '较上次升高',
  down: '较上次降低',
  flat: '与上次持平',
  none: '暂无可比的上次结果',
}

/** 由批量 metric-summaries 单项生成卡片；后端已按与 /trends 相同语义选出最新点与变化 */
export function toFollowCardFromSummary(code: string, name: string, s: MetricLatestSummary | undefined): FollowCard {
  if (!s) return { code, name, value: null, unit: null, date: null, direction: 'none', abnormalFlag: null }
  const latest = s.latest
  return {
    code,
    name: s.standardName || name,
    value: latest?.originalValue ?? null,
    unit: latest?.unitText ?? s.standardUnit ?? null,
    date: latest?.examinationDate ?? null,
    direction: latest ? trendDirection(s.change) : 'none',
    abnormalFlag: latest?.abnormalFlag ?? null,
  }
}

/**
 * 取所有可比组中检查日期最新的精确结果（同日取 observationId 较大者），
 * 方向只与同组上一次比较，避免跨单位/方法比较。
 */
export function toFollowCard(code: string, view: TrendView | null): FollowCard {
  const empty: FollowCard = {
    code,
    name: view?.metric.standardName ?? code,
    value: null,
    unit: view?.metric.standardUnit ?? null,
    date: null,
    direction: 'none',
    abnormalFlag: null,
  }
  if (!view) return empty
  let best: FollowCard | null = null
  let bestId = -1
  for (const group of view.groups) {
    for (const p of group.points) {
      const newer =
        !best || p.examinationDate > (best.date ?? '') || (p.examinationDate === best.date && p.observationId > bestId)
      if (newer) {
        bestId = p.observationId
        best = {
          ...empty,
          value: p.originalValue,
          unit: group.unitText ?? view.metric.standardUnit,
          date: p.examinationDate,
          direction: trendDirection(p.change),
          abnormalFlag: p.abnormalFlag,
        }
      }
    }
  }
  return best ?? empty
}
