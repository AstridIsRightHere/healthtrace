import { describe, expect, it } from 'vitest'
import { isRawMetric, metricOptionLabel, pickDefaultRange } from './trendRange'

// 固定“今天”为 2026-10-27（本地时间），避免测试随日期漂移
const TODAY = new Date(2026, 9, 27)

describe('pickDefaultRange', () => {
  it('无最近日期时退回 30 天', () => {
    expect(pickDefaultRange(null, TODAY)).toBe('30d')
    expect(pickDefaultRange(undefined, TODAY)).toBe('30d')
    expect(pickDefaultRange('2026/10/01', TODAY)).toBe('30d')
  })

  it('选能覆盖最近一次结果的最短区间（含首尾）', () => {
    expect(pickDefaultRange('2026-10-21', TODAY)).toBe('7d')
    expect(pickDefaultRange('2026-10-20', TODAY)).toBe('30d')
    expect(pickDefaultRange('2026-09-28', TODAY)).toBe('30d')
    expect(pickDefaultRange('2026-09-10', TODAY)).toBe('1y')
    expect(pickDefaultRange('2025-10-28', TODAY)).toBe('1y')
    expect(pickDefaultRange('2025-10-27', TODAY)).toBe('5y')
  })

  it('超过 5 年仍返回 5y', () => {
    expect(pickDefaultRange('2010-01-01', TODAY)).toBe('5y')
  })
})

describe('metric labels', () => {
  it('识别原名汇总项目', () => {
    expect(isRawMetric('RAW:促甲状腺激素')).toBe(true)
    expect(isRawMetric('WBC')).toBe(false)
    expect(isRawMetric(null)).toBe(false)
  })

  it('下拉文字带次数，原名项目标注未标准化', () => {
    expect(metricOptionLabel({ code: 'WBC', standardName: '白细胞计数', count: 2 })).toBe('白细胞计数（2 次）')
    expect(metricOptionLabel({ code: 'RAW:TSH', standardName: '促甲状腺激素', count: 1 })).toBe(
      '促甲状腺激素（1 次） · 未标准化',
    )
  })
})
