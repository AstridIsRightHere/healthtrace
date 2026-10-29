import { api } from './index'

/** 对应后端 FollowedMetricView：按 sortOrder 升序 */
export interface FollowedMetric {
  metricCode: string
  name: string
  sortOrder: number
}

/** 标准指标字典项（GET /api/v1/metric-definitions，字典顺序） */
export interface MetricDefinitionOption {
  code: string
  name: string
}

export const followedMetricApi = {
  /** GET /api/v1/patient-profiles/{profileId}/followed-metrics */
  list: (profileId: number) =>
    api.get<FollowedMetric[]>(`/patient-profiles/${profileId}/followed-metrics`).then((res) => res.data),

  /**
   * POST /api/v1/patient-profiles/{profileId}/followed-metrics
   * 提交完整的有序列表（整体替换）：新增、取消关注、拖序都通过这一个接口保存。
   */
  save: (profileId: number, metricCodes: string[]) =>
    api
      .post<FollowedMetric[]>(`/patient-profiles/${profileId}/followed-metrics`, { metricCodes })
      .then((res) => res.data),

  /** GET /api/v1/metric-definitions：可关注的标准指标 */
  definitions: () => api.get<MetricDefinitionOption[]>('/metric-definitions').then((res) => res.data),
}
