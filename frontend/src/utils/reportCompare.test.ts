import { describe, expect, it } from 'vitest'
import type { ComparisonRow, ObservationCell } from '../api/compare'
import { cellText, defaultPair, filterRows, reportLabel, rowReference, rowStatusText } from './reportCompare'

function cell(value: string, unit: string | null, ref: string | null = null): ObservationCell {
  return {
    observationId: 1,
    originalValue: value,
    numericValue: Number(value),
    resultKind: 'EXACT',
    unitText: unit,
    specimenText: null,
    methodText: null,
    referenceText: ref,
    abnormalFlag: 'NORMAL',
  }
}

function row(key: string, status: ComparisonRow['status']): ComparisonRow {
  return { key, name: key, standard: true, status, base: null, target: null, change: null }
}

describe('reportCompare', () => {
  it('reportLabel 组合日期与标题', () => {
    expect(reportLabel({ title: '血常规', examinationDate: '2024-05-01' })).toBe('2024-05-01 · 血常规')
    expect(reportLabel({ title: null, examinationDate: null })).toBe('日期未填写')
  })

  it('defaultPair：此次=最新，上次=第二新；不足两份返回 null', () => {
    expect(defaultPair([{ id: 9 }, { id: 5 }, { id: 2 }])).toEqual({ baseId: 5, targetId: 9 })
    expect(defaultPair([{ id: 9 }])).toBeNull()
  })

  it('filterRows 只看两份都有时去掉单侧行', () => {
    const rows = [row('A', 'COMPARABLE'), row('B', 'BASE_ONLY'), row('C', 'TARGET_ONLY'), row('D', 'NOT_COMPARABLE')]
    expect(filterRows(rows, true).map((r) => r.key)).toEqual(['A', 'D'])
    expect(filterRows(rows, false)).toHaveLength(4)
  })

  it('不可比行有说明，可比行无说明，且不出现好转/恶化', () => {
    expect(rowStatusText('COMPARABLE')).toBe('')
    expect(rowStatusText('NOT_COMPARABLE')).toContain('不做比较')
    for (const s of ['COMPARABLE', 'NOT_COMPARABLE', 'AMBIGUOUS', 'BASE_ONLY', 'TARGET_ONLY'] as const) {
      expect(rowStatusText(s)).not.toMatch(/好转|恶化/)
    }
  })

  it('cellText 与 rowReference', () => {
    expect(cellText(null)).toBe('—')
    expect(cellText(cell('5.6', 'mmol/L'))).toBe('5.6 mmol/L')
    expect(cellText(cell('5.6', null))).toBe('5.6')
    expect(rowReference({ base: cell('1', null, '3-5'), target: cell('2', null, '3.9-6.1') })).toBe('3.9-6.1')
    expect(rowReference({ base: cell('1', null, '3-5'), target: null })).toBe('3-5')
    expect(rowReference({ base: null, target: null })).toBe('未提供')
  })
})
