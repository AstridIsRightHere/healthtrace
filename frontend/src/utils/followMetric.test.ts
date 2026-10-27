import { describe, expect, it } from 'vitest'
import type { TrendPoint, TrendView } from '../api/trend'
import { toFollowCard, trendDirection } from './followMetric'

function point(id: number, date: string, value: number, absolute: number | null): TrendPoint {
  return {
    observationId: id,
    reportId: 1,
    examinationDate: date,
    originalValue: String(value),
    numericValue: value,
    referenceText: null,
    referenceLow: null,
    referenceHigh: null,
    abnormalFlag: 'NORMAL',
    change: absolute === null ? null : { absolute, percent: null, note: null },
  }
}

function view(groups: { unit: string; points: TrendPoint[] }[]): TrendView {
  return {
    metric: { code: 'WBC', standardName: '白细胞计数', standardUnit: '×10⁹/L', comparabilityNote: null },
    range: { from: '2021-01-01', to: '2026-10-28' },
    groups: groups.map((g, i) => ({
      groupKey: `g${i}`,
      unitText: g.unit,
      specimenText: null,
      methodText: null,
      matchesStandardUnit: true,
      points: g.points,
      nonNumeric: [],
    })),
    unitWarning: false,
  }
}

describe('trendDirection', () => {
  it('maps change sign to arrow direction', () => {
    expect(trendDirection({ absolute: 1.2, percent: 10, note: null })).toBe('up')
    expect(trendDirection({ absolute: -0.5, percent: -5, note: null })).toBe('down')
    expect(trendDirection({ absolute: 0, percent: 0, note: null })).toBe('flat')
  })

  it('gives no direction without a comparable previous point', () => {
    expect(trendDirection(null)).toBe('none')
    expect(trendDirection({ absolute: null, percent: null, note: null })).toBe('none')
    expect(trendDirection({ absolute: 2, percent: null, note: 'SAME_DAY_AMBIGUOUS' })).toBe('none')
  })
})

describe('toFollowCard', () => {
  it('returns an empty card when there is no data', () => {
    const card = toFollowCard('HGB', null)
    expect(card).toMatchObject({ code: 'HGB', name: 'HGB', value: null, direction: 'none' })
    expect(toFollowCard('WBC', view([])).name).toBe('白细胞计数')
  })

  it('picks the latest point across groups with its own unit and direction', () => {
    const card = toFollowCard(
      'WBC',
      view([
        { unit: '×10⁹/L', points: [point(1, '2026-01-01', 5, null), point(2, '2026-05-01', 6, 1)] },
        { unit: '10^9/L', points: [point(3, '2026-09-01', 4.2, null)] },
      ]),
    )
    expect(card).toMatchObject({ value: '4.2', unit: '10^9/L', date: '2026-09-01', direction: 'none' })
  })

  it('breaks same-day ties by larger observation id', () => {
    const card = toFollowCard('WBC', view([{ unit: 'x', points: [point(7, '2026-05-01', 6, -1), point(5, '2026-05-01', 8, 2)] }]))
    expect(card).toMatchObject({ value: '6', direction: 'down' })
  })
})
