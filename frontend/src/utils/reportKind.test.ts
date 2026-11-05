import { describe, expect, it } from 'vitest'
import type { TimelineEntry } from '../api/visit'
import { conclusionSummary, groupTimelineByYear, parseKindFilter, reportKindLabel } from './reportKind'

const entry = (date: string | null): TimelineEntry => ({ type: 'REPORT', date, visit: null, report: null })

describe('parseKindFilter', () => {
  it('accepts known kinds case-insensitively', () => {
    expect(parseKindFilter('imaging')).toBe('IMAGING')
    expect(parseKindFilter('STRUCTURED')).toBe('STRUCTURED')
    expect(parseKindFilter('Unstructured')).toBe('UNSTRUCTURED')
  })

  it('falls back to ALL for missing or unknown values', () => {
    expect(parseKindFilter(undefined)).toBe('ALL')
    expect(parseKindFilter('XRAY')).toBe('ALL')
    expect(parseKindFilter(['IMAGING'])).toBe('ALL')
  })
})

describe('report kind labels', () => {
  it('maps kinds and defaults to structured', () => {
    expect(reportKindLabel('IMAGING')).toBe('影像报告')
    expect(reportKindLabel(null)).toBe('检验报告')
  })

  it('summarizes conclusion by code points', () => {
    expect(conclusionSummary('  肝胆\n胰脾  未见异常 ')).toBe('肝胆 胰脾 未见异常')
    expect(conclusionSummary('😀'.repeat(5), 3)).toBe('😀😀😀…')
    expect(conclusionSummary(null)).toBe('')
  })
})

describe('groupTimelineByYear', () => {
  it('keeps order and groups by year with undated last', () => {
    const groups = groupTimelineByYear([entry('2026-10-01'), entry('2026-01-01'), entry('2025-12-31'), entry(null)])
    expect(groups.map((g) => [g.year, g.entries.length])).toEqual([
      ['2026', 2],
      ['2025', 1],
      ['日期未填写', 1],
    ])
  })
})
