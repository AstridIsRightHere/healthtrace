import { describe, expect, it } from 'vitest'
import type { ReportListItem } from '../api/report'
import {
  formatShortDate,
  formatVisitDate,
  groupByYear,
  linkableReports,
  linkedReportLabel,
  localIsoDate,
  toPayload,
  validateVisitForm,
  visitTitle,
} from './visit'

const report = (id: number, examinationDate: string | null): ReportListItem => ({
  id,
  title: `报告${id}`,
  reportType: 'OTHER',
  examinationDate,
  processingStatus: 'CONFIRMED',
  createdAt: '2026-10-01T00:00:00',
  photoCount: 1,
  coverPhotoId: null,
  coverHasPreview: false,
  lastOcrErrorCode: null,
})

describe('visit date formatting', () => {
  it('formats ISO dates in Chinese and keeps malformed input', () => {
    expect(formatVisitDate('2026-10-27')).toBe('2026年10月27日')
    expect(formatVisitDate('2026-01-05')).toBe('2026年1月5日')
    expect(formatShortDate('2026-01-05')).toBe('1月5日')
    expect(formatVisitDate('bad')).toBe('bad')
  })

  it('uses local calendar date', () => {
    expect(localIsoDate(new Date(2026, 9, 27, 23, 59))).toBe('2026-10-27')
    expect(localIsoDate(new Date(2026, 0, 3))).toBe('2026-01-03')
  })
})

describe('visit labels', () => {
  it('joins hospital and department', () => {
    expect(visitTitle({ hospitalText: '市第一医院', department: '呼吸内科' })).toBe('市第一医院 · 呼吸内科')
    expect(visitTitle({ hospitalText: '市第一医院', department: null })).toBe('市第一医院')
  })

  it('falls back for missing report title/date', () => {
    expect(linkedReportLabel({ title: null, examinationDate: null })).toBe('检查日期未填写 · 未命名报告')
    expect(linkedReportLabel({ title: '血常规', examinationDate: '2026-10-20' })).toBe('2026-10-20 · 血常规')
  })
})

describe('validateVisitForm', () => {
  const base = { visitDate: '2026-10-27', hospitalText: '市第一医院', department: '', note: '' }

  it('accepts a valid form', () => {
    expect(validateVisitForm(base, '2026-10-27')).toEqual({})
  })

  it('requires date and hospital, rejects future date', () => {
    expect(validateVisitForm({ ...base, visitDate: '', hospitalText: '  ' }, '2026-10-27')).toEqual({
      visitDate: '请填写就诊日期',
      hospitalText: '请填写医院',
    })
    expect(validateVisitForm({ ...base, visitDate: '2026-10-28' }, '2026-10-27').visitDate).toBe('就诊日期不能晚于今天')
  })

  it('counts length by code points', () => {
    expect(validateVisitForm({ ...base, department: '😀'.repeat(80) }, '2026-10-27').department).toBeUndefined()
    expect(validateVisitForm({ ...base, department: '😀'.repeat(81) }, '2026-10-27').department).toBe(
      '科室不能超过 80 个字符',
    )
  })

  it('builds trimmed payload with null optionals', () => {
    expect(toPayload({ ...base, hospitalText: ' 市第一医院 ', department: ' ', note: ' 复查 ' })).toEqual({
      visitDate: '2026-10-27',
      hospitalText: '市第一医院',
      department: null,
      note: '复查',
    })
  })
})

describe('linkableReports', () => {
  it('excludes linked, dedupes and sorts by date desc with nulls last', () => {
    const all = [report(1, '2026-01-01'), report(2, null), report(3, '2026-05-01'), report(4, '2026-03-01'), report(3, '2026-05-01')]
    const result = linkableReports(all, { reports: [{ id: 4, title: null, reportType: 'OTHER', examinationDate: null, processingStatus: 'CONFIRMED' }] })
    expect(result.map((r) => r.id)).toEqual([3, 1, 2])
  })
})

describe('groupByYear', () => {
  it('groups consecutive visits by year in given order', () => {
    const groups = groupByYear([{ visitDate: '2026-10-27' }, { visitDate: '2026-03-01' }, { visitDate: '2025-12-31' }])
    expect(groups.map((g) => [g.year, g.visits.length])).toEqual([
      ['2026', 2],
      ['2025', 1],
    ])
  })
})
