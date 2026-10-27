// 就诊时间线（FR-013）的纯函数逻辑：日期格式化、表单校验、可关联报告筛选、按年分组。
import type { ReportListItem } from '../api/report'
import type { LinkedReport, Visit, VisitPayload } from '../api/visit'

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

/** 本地时区的 yyyy-MM-dd（用于 <input type="date"> 默认值与 max） */
export function localIsoDate(now: Date = new Date()): string {
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** "2026-10-27" → "2026年10月27日"；格式不对时原样返回 */
export function formatVisitDate(iso: string): string {
  const m = ISO_DATE.exec(iso)
  if (!m) return iso
  return `${m[1]}年${Number(m[2])}月${Number(m[3])}日`
}

/** "10月27日"：时间线内同一年分组下用短格式 */
export function formatShortDate(iso: string): string {
  const m = ISO_DATE.exec(iso)
  if (!m) return iso
  return `${Number(m[2])}月${Number(m[3])}日`
}

/** "市第一医院 · 呼吸内科"；科室为空只显示医院 */
export function visitTitle(v: Pick<Visit, 'hospitalText' | 'department'>): string {
  return v.department ? `${v.hospitalText} · ${v.department}` : v.hospitalText
}

/** 关联报告的显示文字："2026-10-20 · 血常规"，无标题/日期时给出兜底 */
export function linkedReportLabel(r: Pick<LinkedReport, 'title' | 'examinationDate'>): string {
  const date = r.examinationDate ?? '检查日期未填写'
  return `${date} · ${r.title?.trim() || '未命名报告'}`
}

export interface VisitForm {
  visitDate: string
  hospitalText: string
  department: string
  note: string
}

export type VisitFormErrors = Partial<Record<keyof VisitForm, string>>

const codePoints = (s: string) => Array.from(s).length

/** 与后端 VisitService 一致的前端校验；today 为本地日期 yyyy-MM-dd */
export function validateVisitForm(form: VisitForm, today: string): VisitFormErrors {
  const errors: VisitFormErrors = {}
  if (!form.visitDate || !ISO_DATE.test(form.visitDate)) {
    errors.visitDate = '请填写就诊日期'
  } else if (form.visitDate > today) {
    errors.visitDate = '就诊日期不能晚于今天'
  }
  const hospital = form.hospitalText.trim()
  if (!hospital) errors.hospitalText = '请填写医院'
  else if (codePoints(hospital) > 160) errors.hospitalText = '医院不能超过 160 个字符'
  if (codePoints(form.department.trim()) > 80) errors.department = '科室不能超过 80 个字符'
  if (codePoints(form.note.trim()) > 2000) errors.note = '备注不能超过 2000 个字符'
  return errors
}

/** 表单 → 请求体：去首尾空白，空的选填项传 null */
export function toPayload(form: VisitForm): VisitPayload {
  const opt = (s: string) => (s.trim() ? s.trim() : null)
  return {
    visitDate: form.visitDate,
    hospitalText: form.hospitalText.trim(),
    department: opt(form.department),
    note: opt(form.note),
  }
}

/** 可关联的报告：排除已关联的，按检查日期倒序（无日期排最后），同 id 去重 */
export function linkableReports(all: ReportListItem[], visit: Pick<Visit, 'reports'>): ReportListItem[] {
  const linked = new Set(visit.reports.map((r) => r.id))
  const seen = new Set<number>()
  return all
    .filter((r) => {
      if (linked.has(r.id) || seen.has(r.id)) return false
      seen.add(r.id)
      return true
    })
    .sort((a, b) => {
      if (a.examinationDate === b.examinationDate) return b.id - a.id
      if (!a.examinationDate) return 1
      if (!b.examinationDate) return -1
      return a.examinationDate < b.examinationDate ? 1 : -1
    })
}

/** 时间线按年份分组，保持传入顺序（后端已按就诊日倒序） */
export function groupByYear<T extends Pick<Visit, 'visitDate'>>(visits: T[]): Array<{ year: string; visits: T[] }> {
  const groups: Array<{ year: string; visits: T[] }> = []
  for (const v of visits) {
    const year = v.visitDate.slice(0, 4)
    const last = groups[groups.length - 1]
    if (last && last.year === year) last.visits.push(v)
    else groups.push({ year, visits: [v] })
  }
  return groups
}
