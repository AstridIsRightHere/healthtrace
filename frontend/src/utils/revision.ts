// 正式报告修改留痕（FR-014）的纯逻辑：构建提交内容、判断是否有改动、格式化修改记录
import type { ObservationInput, ObservationView } from '../api/ocr'
import type { ObservationSnapshot, RevisionChange, RevisionView } from '../api/revision'
import type { EditableRow } from '../components/review/types'

export const REASON_MAX_LENGTH = 240

/** 表格行 → 提交内容：去掉首尾空白，空单位/参考范围提交为 null */
export function toObservationInputs(rows: EditableRow[]): ObservationInput[] {
  return rows.map((row) => ({
    id: row.id ?? undefined,
    originalName: row.originalName.trim(),
    originalValue: row.originalValue.trim(),
    unitText: row.unitText.trim() || null,
    referenceText: row.referenceText.trim() || null,
    sourcePhotoId: row.sourcePhotoId,
  }))
}

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
  if (saved.length !== rows.length) return false
  const byId = new Map(saved.map((o) => [o.id, o]))
  return rows.every((row) => {
    if (row.id === null) return false
    const old = byId.get(row.id)
    if (!old) return false
    return (
      norm(old.originalName) === norm(row.originalName) &&
      norm(old.originalValue) === norm(row.originalValue) &&
      norm(old.unitText) === norm(row.unitText) &&
      norm(old.referenceText) === norm(row.referenceText)
    )
  })
}

/** 构建 PATCH 请求体；原因为空时提交 null */
export function buildRevisePayload(version: number, examinationDate: string, reason: string, rows: EditableRow[]) {
  return {
    version,
    examinationDate,
    reason: reason.trim() || null,
    observations: toObservationInputs(rows),
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
