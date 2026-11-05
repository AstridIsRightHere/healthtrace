// 报告种类（任务二）：检验 / 影像 / 其他的显示文字与筛选参数解析，以及合并时间轴的按年分组。
import type { ReportKind } from '../api/report'
import type { TimelineEntry } from '../api/visit'

export const REPORT_KIND_LABELS: Record<ReportKind, string> = {
  STRUCTURED: '检验报告',
  IMAGING: '影像报告',
  UNSTRUCTURED: '其他报告',
}

export type KindFilter = ReportKind | 'ALL'

export const KIND_FILTERS: { value: KindFilter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'STRUCTURED', label: '检验' },
  { value: 'IMAGING', label: '影像' },
  { value: 'UNSTRUCTURED', label: '其他' },
]

/** 路由 query.kind → 筛选值；非法或缺省为 ALL（大小写不敏感） */
export function parseKindFilter(raw: unknown): KindFilter {
  if (typeof raw !== 'string') return 'ALL'
  const upper = raw.toUpperCase()
  return upper === 'STRUCTURED' || upper === 'IMAGING' || upper === 'UNSTRUCTURED' ? upper : 'ALL'
}

export function reportKindLabel(kind: ReportKind | null | undefined): string {
  return kind ? REPORT_KIND_LABELS[kind] : REPORT_KIND_LABELS.STRUCTURED
}

/** 结论摘要：去掉多余空白，超过 max 个字符（按码点）截断加省略号 */
export function conclusionSummary(text: string | null | undefined, max = 60): string {
  const normalized = (text ?? '').replace(/\s+/g, ' ').trim()
  const chars = Array.from(normalized)
  return chars.length > max ? `${chars.slice(0, max).join('')}…` : normalized
}

export interface TimelineYearGroup {
  /** 年份字符串；无日期的条目归入 "日期未填写" */
  year: string
  entries: TimelineEntry[]
}

/** 后端已按日期倒序（null 在后）返回，这里保持顺序按年分组 */
export function groupTimelineByYear(entries: TimelineEntry[]): TimelineYearGroup[] {
  const groups: TimelineYearGroup[] = []
  for (const entry of entries) {
    const year = entry.date && /^\d{4}-/.test(entry.date) ? entry.date.slice(0, 4) : '日期未填写'
    const last = groups[groups.length - 1]
    if (last && last.year === year) last.entries.push(entry)
    else groups.push({ year, entries: [entry] })
  }
  return groups
}
