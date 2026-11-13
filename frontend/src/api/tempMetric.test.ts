import { describe, expect, it } from 'vitest'
import { TEMP_METRIC_PREFIX, isTempMetricCode, parseTempMetricId } from './tempMetric'

describe('tempMetric', () => {
  it('以 TMP: 前缀识别临时指标编码', () => {
    expect(TEMP_METRIC_PREFIX).toBe('TMP:')
    expect(isTempMetricCode('TMP:12')).toBe(true)
    expect(isTempMetricCode('WBC')).toBe(false)
    expect(isTempMetricCode('RAW:同型半胱氨酸')).toBe(false)
  })

  it('从编码解析临时指标 id', () => {
    expect(parseTempMetricId('TMP:12')).toBe(12)
    expect(parseTempMetricId('TMP:1')).toBe(1)
  })

  it('非法编码返回 null，避免拼出错误的删除地址', () => {
    expect(parseTempMetricId('WBC')).toBeNull()
    expect(parseTempMetricId('TMP:')).toBeNull()
    expect(parseTempMetricId('TMP:0')).toBeNull()
    expect(parseTempMetricId('TMP:-3')).toBeNull()
    expect(parseTempMetricId('TMP:12abc')).toBeNull()
    expect(parseTempMetricId('TMP:../1')).toBeNull()
    expect(parseTempMetricId('TMP:99999999999999999999')).toBeNull()
  })
})
