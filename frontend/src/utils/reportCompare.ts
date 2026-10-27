// 报告对比页的纯函数逻辑（FR-010/011）。只做客观描述，不出现“好转/恶化”等判断（TC-010）。
import type { ComparisonRow, ComparisonRowStatus, ObservationCell } from '../api/compare'
import type { ReportGroup, ReportListItem } from '../api/report'

/** 报告选项文字：“2024-05-01 · 血常规” */
export function reportLabel(r: Pick<ReportListItem, 'title' | 'examinationDate'>): string {
  const date = r.examinationDate ?? '日期未填写'
  return r.title ? `${date} · ${r.title}` : date
}

/** 默认对比对：列表已按检查日期倒序，此次=最新一份，上次=第二新的一份；不足两份返回 null */
export function defaultPair(reports: Array<Pick<ReportListItem, 'id'>>): { baseId: number; targetId: number } | null {
  if (reports.length < 2) return null
  return { baseId: reports[1]!.id, targetId: reports[0]!.id }
}

/** 类型胶囊文字：“血常规（3 份）” */
export function groupChipLabel(g: Pick<ReportGroup, 'label' | 'count'>): string {
  return `${g.label}（${g.count} 份）`
}

/**
 * 进入页面时默认选中的类型：URL 指定且存在则用它；否则选第一个可对比（≥2 份）的组
 * （组已按最近检查日期倒序）；都不足两份时选第一个组；没有组返回 null。
 */
export function initialGroupKey(groups: Array<Pick<ReportGroup, 'key' | 'count'>>, preferred?: string | null): string | null {
  if (preferred && groups.some((g) => g.key === preferred)) return preferred
  return (groups.find((g) => g.count >= 2) ?? groups[0])?.key ?? null
}

/** “只看两份都有的指标”：保留两侧均有结果的行（含单位等条件不同而不比较的行） */
export function filterRows(rows: ComparisonRow[], onlyBoth: boolean): ComparisonRow[] {
  if (!onlyBoth) return rows
  return rows.filter((r) => r.status !== 'BASE_ONLY' && r.status !== 'TARGET_ONLY')
}

const STATUS_TEXT: Record<ComparisonRowStatus, string> = {
  COMPARABLE: '',
  NOT_COMPARABLE: '单位、标本或方法不同，不做比较',
  AMBIGUOUS: '同一报告中该项目有多条结果，不做比较',
  BASE_ONLY: '此次未检查',
  TARGET_ONLY: '上次未检查',
}

/** 不计算变化时的说明文字；可比行返回空串 */
export function rowStatusText(status: ComparisonRowStatus): string {
  return STATUS_TEXT[status]
}

/** 单元格文字：原始结果 + 单位，缺失时为“—” */
export function cellText(cell: ObservationCell | null): string {
  if (!cell) return '—'
  return cell.unitText ? `${cell.originalValue} ${cell.unitText}` : cell.originalValue
}

/** 参考范围：优先此次，没有再用上次 */
export function rowReference(row: Pick<ComparisonRow, 'base' | 'target'>): string {
  return row.target?.referenceText || row.base?.referenceText || '未提供'
}
