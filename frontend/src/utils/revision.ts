// 正式报告修改留痕（FR-014）的纯逻辑：构建提交内容、判断是否有改动、格式化修改记录
import type { ObservationInput, ObservationView } from '../api/ocr'
import type {
  CreateRevisionCommand,
  ObservationSnapshot,
  RevisionChange,
  RevisionOperation,
  RevisionView,
} from '../api/revision'
import type { EditableRow } from '../components/review/types'

export const REASON_MAX_LENGTH = 240

function norm(text: string | null | undefined): string {
  return (text ?? '').trim()
}

/** 与已保存内容相比是否没有任何改动（顺序变化不算改动） */
export function hasNoChange(
  saved: ObservationView[],
  savedDate: string | null,
  rows: EditableRow[],
  date: string,
): boolean {
  if (norm(savedDate) !== norm(date)) return false
  return buildOperations(saved, rows).length === 0
}

/** 表格行 → 指标字段（不带 id，id 由操作的 observationId 表达） */
function toInput(row: EditableRow): ObservationInput {
  return {
    originalName: row.originalName.trim(),
    originalValue: row.originalValue.trim(),
    unitText: row.unitText.trim() || null,
    referenceText: row.referenceText.trim() || null,
    sourcePhotoId: row.sourcePhotoId,
  }
}

function sameContent(old: ObservationView, row: EditableRow): boolean {
  return (
    norm(old.originalName) === norm(row.originalName) &&
    norm(old.originalValue) === norm(row.originalValue) &&
    norm(old.unitText) === norm(row.unitText) &&
    norm(old.referenceText) === norm(row.referenceText)
  )
}

/**
 * 对比已保存的正式结果与编辑后的表格，得出指标级操作：
 * 新行 → ADD；内容有变的已有行 → UPDATE；表格里消失的已有行 → DELETE；未变的行不提交。
 */
export function buildOperations(saved: ObservationView[], rows: EditableRow[]): RevisionOperation[] {
  const byId = new Map(saved.map((o) => [o.id, o]))
  const kept = new Set<number>()
  const ops: RevisionOperation[] = []
  for (const row of rows) {
    const old = row.id === null ? undefined : byId.get(row.id)
    if (!old) {
      ops.push({ op: 'ADD', observation: toInput(row) })
      continue
    }
    kept.add(old.id)
    if (!sameContent(old, row)) ops.push({ op: 'UPDATE', observationId: old.id, observation: toInput(row) })
  }
  for (const old of saved) {
    if (!kept.has(old.id)) ops.push({ op: 'DELETE', observationId: old.id })
  }
  return ops
}

/** 构建 POST /revisions 请求体；检查日期未变时提交 null（表示不修改），原因为空时提交 null */
export function buildRevisionCommand(
  version: number,
  saved: ObservationView[],
  savedDate: string | null,
  rows: EditableRow[],
  date: string,
  reason: string,
): CreateRevisionCommand {
  return {
    version,
    reason: reason.trim() || null,
    examinationDate: norm(savedDate) === norm(date) ? null : norm(date) || null,
    operations: buildOperations(saved, rows),
  }
}

/** 数值 + 单位，如“6.1 10^9/L” */
function valueWithUnit(s: ObservationSnapshot): string {
  const unit = norm(s.unitText)
  return unit ? `${norm(s.originalValue)} ${unit}` : norm(s.originalValue)
}

/** 单条修改的展示文案，如“修改 白细胞 6.1 → 7.2 10^9/L” */
export function formatChange(change: RevisionChange): string {
  const { before, after } = change
  if (change.type === 'ADDED' && after) return `新增 ${norm(after.originalName)} ${valueWithUnit(after)}`
  if (change.type === 'REMOVED' && before) return `删除 ${norm(before.originalName)} ${valueWithUnit(before)}`
  if (change.type === 'UPDATED' && before && after) {
    const name =
      norm(before.originalName) === norm(after.originalName)
        ? norm(after.originalName)
        : `${norm(before.originalName)} → ${norm(after.originalName)}`
    const beforeUnit = norm(before.unitText)
    const afterUnit = norm(after.unitText)
    // 单位相同时只在末尾写一次：“6.1 → 7.2 10^9/L”
    const value =
      beforeUnit === afterUnit
        ? `${norm(before.originalValue)} → ${valueWithUnit(after)}`
        : `${valueWithUnit(before)} → ${valueWithUnit(after)}`
    let line = `修改 ${name} ${value}`
    const beforeRef = norm(before.referenceText)
    const afterRef = norm(after.referenceText)
    if (beforeRef !== afterRef) line += `（参考范围 ${beforeRef || '无'} → ${afterRef || '无'}）`
    return line
  }
  return '内容变更'
}

/** 检查日期变更文案；未变更返回空串 */
export function formatDateChange(change: RevisionView['examinationDate']): string {
  if (!change || change.before === change.after) return ''
  return `检查日期 ${change.before || '未填写'} → ${change.after || '未填写'}`
}

export function formatVersions(revision: Pick<RevisionView, 'fromVersion' | 'toVersion'>): string {
  return `第 ${revision.fromVersion} 版 → 第 ${revision.toVersion} 版`
}

/** 后端时间为 UTC（无时区后缀），按 UTC 解析再本地显示 */
function parseUtc(iso: string): Date {
  return new Date(/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : `${iso}Z`)
}

export function formatRevisionTime(iso: string): string {
  const date = parseUtc(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

/** 最新的修改在前：按创建时间倒序，时间相同（或无法解析）按 id 倒序 */
export function sortRevisions(list: RevisionView[]): RevisionView[] {
  return [...list].sort((a, b) => {
    const diff = parseUtc(b.createdAt).getTime() - parseUtc(a.createdAt).getTime()
    return Number.isNaN(diff) || diff === 0 ? b.id - a.id : diff
  })
}
