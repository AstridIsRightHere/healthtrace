import { describe, expect, it } from 'vitest'
import { TEMP_METRIC_PREFIX, isTempMetricCode } from './tempMetric'

describe('tempMetric', () => {
  it('以 TMP: 前缀识别临时指标编码', () => {
    expect(TEMP_METRIC_PREFIX).toBe('TMP:')
    expect(isTempMetricCode('TMP:12')).toBe(true)
    expect(isTempMetricCode('WBC')).toBe(false)
    expect(isTempMetricCode('RAW:同型半胱氨酸')).toBe(false)
  })
})
