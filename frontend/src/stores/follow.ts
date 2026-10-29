import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { followedMetricApi, type FollowedMetric } from '../api/followedMetric'

/**
 * 首页关注指标（FR-009 / FR-012）。关注配置以后端 followed_metric 表为准，
 * 这里只在内存中缓存当前档案的结果（ADR-010），不在前端内置任何默认项：
 * 新档案的默认 6 项由后端在建档时写入。
 * 加载失败时把错误向上抛出，由页面显示“加载失败，请点击重试”，不伪装为空列表。
 */
export const useFollowStore = defineStore('follow', () => {
  const items = ref<FollowedMetric[]>([])
  /** 已成功加载的档案 id；null 表示尚未加载或已重置 */
  const loadedProfileId = ref<number | null>(null)

  const codes = computed(() => items.value.map((item) => item.metricCode))

  async function load(profileId: number, force = false): Promise<void> {
    if (!force && loadedProfileId.value === profileId) return
    const result = await followedMetricApi.list(profileId)
    items.value = result
    loadedProfileId.value = profileId
  }

  /** 提交完整的有序列表（新增、取消、排序一次保存），成功后以服务端返回为准 */
  async function save(profileId: number, metricCodes: string[]): Promise<void> {
    const result = await followedMetricApi.save(profileId, metricCodes)
    items.value = result
    loadedProfileId.value = profileId
  }

  function reset(): void {
    items.value = []
    loadedProfileId.value = null
  }

  return { items, codes, loadedProfileId, load, save, reset }
})
