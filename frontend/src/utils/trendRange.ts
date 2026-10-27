// 趋势页默认时间区间：URL 未指定区间时，选能覆盖该指标最近一次结果的最短预设区间，
// 避免旧报告的数据落在默认 30 天之外导致页面“空白”。
import type { MetricSummary, TrendRangeKey } from '../api/trend'

/** 未纳入标准字典、按报告原名汇总的项目（后端 code 以 RAW: 开头） */
export function isRawMetric(code: string | null | undefined): boolean {
  return typeof code === 'string' && code.startsWith('RAW:')
}

/** 下拉选项文字：标准指标显示次数；原名汇总项目额外标注“未标准化” */
export function metricOptionLabel(m: Pick<MetricSummary, 'code' | 'standardName' | 'count'>): string {
  const base = `${m.standardName}（${m.count} 次）`
  return isRawMetric(m.code) ? `${base} · 未标准化` : base
}

const PRESETS: Array<{ key: Exclude<TrendRangeKey, 'custom'>; days?: number; years?: number }> = [
  { key: '7d', days: 7 },
  { key: '30d', days: 30 },
  { key: '1y', years: 1 },
  { key: '5y', years: 5 },
]

/** 'YYYY-MM-DD' 解析为本地日期（零点），格式不对返回 null */
function parseDate(value: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!m) return null
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
}

/** 与后端 TrendRange.fromFor 一致：起点 = 今天往前 N 天 + 1 / N 年 + 1 天（含首尾） */
function presetStart(today: Date, preset: (typeof PRESETS)[number]): Date {
  const d = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  if (preset.days) d.setDate(d.getDate() - preset.days + 1)
  if (preset.years) {
    d.setFullYear(d.getFullYear() - preset.years)
    d.setDate(d.getDate() + 1)
  }
  return d
}

/**
 * 根据最近一次结果日期选默认区间；无日期或更早时退回 5y（再早需用户自定义）。
 */
export function pickDefaultRange(latestDate: string | null | undefined, today: Date = new Date()): TrendRangeKey {
  const latest = latestDate ? parseDate(latestDate) : null
  if (!latest) return '30d'
  for (const preset of PRESETS) {
    if (latest >= presetStart(today, preset)) return preset.key
  }
  return '5y'
}
