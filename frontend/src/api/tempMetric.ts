import { api } from './index'

/** 临时指标编码前缀：后端以 "TMP:{id}" 表示临时指标（FR-009，docs/31） */
export const TEMP_METRIC_PREFIX = 'TMP:'

export function isTempMetricCode(code: string): boolean {
  return code.startsWith(TEMP_METRIC_PREFIX)
}

/** "TMP:12" → 12；不是合法临时指标编码时返回 null */
export function parseTempMetricId(code: string): number | null {
  if (!isTempMetricCode(code)) return null
  const raw = code.slice(TEMP_METRIC_PREFIX.length)
  if (!/^[1-9]\d*$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) ? id : null
}

/** 对应后端 TempMetricView */
export interface TempMetric {
  code: string
  name: string
  unit: string | null
  observationCount: number
  followed: boolean
}

export const tempMetricApi = {
  /** GET /api/v1/patient-profiles/{profileId}/temporary-metrics */
  list: (profileId: number) =>
    api.get<TempMetric[]>(`/patient-profiles/${profileId}/temporary-metrics`).then((res) => res.data),

  /**
   * POST /api/v1/patient-profiles/{profileId}/temporary-metrics
   * 把一条未标准化的已确认结果设为临时指标；同名同单位的已有临时指标会自动归并（DEC-015）。
   */
  create: (profileId: number, observationId: number, follow: boolean) =>
    api
      .post<TempMetric>(`/patient-profiles/${profileId}/temporary-metrics`, { observationId, follow })
      .then((res) => res.data),

  /**
   * DELETE /api/v1/patient-profiles/{profileId}/temporary-metrics/{tempMetricId}
   * 删除临时指标：同时取消关注，关联的结果回到“未标准化”，报告与结果本身不删除。
   */
  remove: (profileId: number, tempMetricId: number) =>
    api.delete<void>(`/patient-profiles/${profileId}/temporary-metrics/${tempMetricId}`).then(() => undefined),
}
