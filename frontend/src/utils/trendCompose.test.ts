import { describe, expect, it } from 'vitest'
import type { TrendGroup, TrendPoint, TrendView } from '../api/trend'
import { comparabilityKey, composeTrends, conditionLabel, normalizeCondition, parseMetricCodes } from './trendCompose'
import { buildMultiSeriesOption } from './trendOption'

function point(id: number, date: string, value: number): TrendPoint {
  return {
    observationId: id,
    reportId: 100 + id,
    examinationDate: date,
    originalValue: String(value),
    numericValue: value,
    referenceText: null,
    referenceLow: null,
    referenceHigh: null,
    abnormalFlag: 'NORMAL',
    change: null,
  } as TrendPoint
}

function group(key: string, unit: string | null, points: TrendPoint[], specimen: string | null = '血清'): TrendGroup {
  return {
    groupKey: key,
    unitText: unit,
    specimenText: specimen,
    methodText: null,
    matchesStandardUnit: true,
    points,
    nonNumeric: [],
  }
}

function trend(code: string, groups: TrendGroup[]): TrendView {
  return {
    metric: { code, standardName: code, standardUnit: 'mmol/L' },
    range: { from: '2024-01-01', to: '2024-12-31' },
    groups,
    unitWarning: groups.length > 1,
  } as unknown as TrendView
}

const two = () => [point(1, '2024-02-01', 5), point(2, '2024-05-01', 6)]

describe('normalizeCondition / comparabilityKey', () => {
  it('只去除空白，不改大小写', () => {
    expect(normalizeCondition(' mmol / L ')).toBe('mmol/L')
    expect(normalizeCondition(null)).toBe('')
    const a = comparabilityKey({ unitText: 'mIU/L', specimenText: null, methodText: null }, true)
    const b = comparabilityKey({ unitText: 'miu/l', specimenText: null, methodText: null }, true)
    expect(a).not.toBe(b)
  })

  it('标准与未标准化指标的键不同', () => {
    const g = { unitText: 'mmol/L', specimenText: null, methodText: null }
    expect(comparabilityKey(g, true)).not.toBe(comparabilityKey(g, false))
  })
})

describe('composeTrends', () => {
  it('同单位同标本同方法的指标合并到一张图', () => {
    const r = composeTrends([trend('GLU', [group('g1', 'mmol/L', two())]), trend('TC', [group('g1', 'mmol / L', two())])])
    expect(r.panels).toHaveLength(1)
    expect(r.panels[0]!.series.map((s) => s.metric.code)).toEqual(['GLU', 'TC'])
    expect(r.splitByComparability).toBe(false)
  })

  it('单位不同时分图并标记 splitByComparability', () => {
    const r = composeTrends([trend('GLU', [group('g1', 'mmol/L', two())]), trend('ALT', [group('g1', 'U/L', two())])])
    expect(r.panels).toHaveLength(2)
    expect(r.splitByComparability).toBe(true)
  })

  it('标本不同也分图', () => {
    const r = composeTrends([
      trend('GLU', [group('g1', 'mmol/L', two(), '血清')]),
      trend('TC', [group('g1', 'mmol/L', two(), '全血')]),
    ])
    expect(r.panels).toHaveLength(2)
  })

  it('未标准化指标不与标准指标同图', () => {
    const r = composeTrends([trend('GLU', [group('g1', 'mmol/L', two())]), trend('RAW:X', [group('g1', 'mmol/L', two())])])
    expect(r.panels).toHaveLength(2)
    expect(r.panels[1]!.standard).toBe(false)
  })

  it('1 个点进入单点卡片，0 个点进入非数值列表', () => {
    const r = composeTrends([
      trend('GLU', [group('g1', 'mmol/L', [point(1, '2024-02-01', 5)])]),
      trend('TC', [group('g1', 'mmol/L', [])]),
    ])
    expect(r.panels).toHaveLength(0)
    expect(r.singles.map((s) => s.metric.code)).toEqual(['GLU'])
    expect(r.noNumeric.map((s) => s.metric.code)).toEqual(['TC'])
  })

  it('点按日期升序排列', () => {
    const r = composeTrends([trend('GLU', [group('g1', 'mmol/L', [point(2, '2024-05-01', 6), point(1, '2024-02-01', 5)])])])
    expect(r.panels[0]!.series[0]!.points.map((p) => p.observationId)).toEqual([1, 2])
  })
})

describe('conditionLabel / parseMetricCodes', () => {
  it('拼接可比条件', () => {
    expect(conditionLabel({ unitText: 'mmol/L', specimenText: '血清', methodText: '酶法' })).toBe('mmol/L · 血清 · 酶法')
    expect(conditionLabel({ unitText: null, specimenText: null, methodText: null })).toBe('单位未标注')
  })

  it('解析逗号分隔、去重、去空并限制数量', () => {
    expect(parseMetricCodes('A, B,,A,C', 2)).toEqual(['A', 'B'])
    expect(parseMetricCodes(['A', 'B,C'], 6)).toEqual(['A', 'B', 'C'])
    expect(parseMetricCodes(undefined, 6)).toEqual([])
  })
})

describe('buildMultiSeriesOption', () => {
  const range = { from: '2024-01-01', to: '2024-12-31' }

  it('多个系列：每个系列一条线并显示图例', () => {
    const opt = buildMultiSeriesOption(
      [
        { name: '血糖', group: group('g1', 'mmol/L', two()) },
        { name: '总胆固醇', group: group('g1', 'mmol/L', two()) },
      ],
      range,
      'mmol/L',
    )
    const series = opt.series as Array<{ name: string; markArea?: unknown }>
    expect(series.map((s) => s.name)).toEqual(['血糖', '总胆固醇'])
    expect(series.every((s) => s.markArea === undefined)).toBe(true)
    expect(opt.legend).toBeTruthy()
  })

  it('tooltip 显示指标名并转义', () => {
    const opt = buildMultiSeriesOption(
      [
        { name: '<b>A</b>', group: group('g1', 'mmol/L', two()) },
        { name: 'B', group: group('g1', 'mmol/L', two()) },
      ],
      range,
      'mmol/L',
    )
    const formatter = (opt.tooltip as { formatter: (p: unknown) => string }).formatter
    const data = (opt.series as Array<{ data: unknown[] }>)[0]!.data[0]
    const html = formatter({ data })
    expect(html).toContain('&lt;b&gt;A&lt;/b&gt;')
    expect(html).not.toContain('<b>A</b>')
  })

  it('单个系列退化为单指标图', () => {
    const opt = buildMultiSeriesOption([{ name: '血糖', group: group('g1', 'mmol/L', two()) }], range, 'mmol/L')
    expect(opt.legend).toBeUndefined()
  })
})
