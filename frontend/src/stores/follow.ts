import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 首页关注指标（FR-012）。
 * 完整的关注配置与拖动排序属于 FR-009，尚未实现：这里先在内存中模拟默认关注项，
 * 不持久化（ADR-010），后续接入后端关注配置时只需替换 codes 的来源。
 */
export const DEFAULT_FOLLOWED_CODES = ['WBC', 'HGB', 'PLT'] as const

export const useFollowStore = defineStore('follow', () => {
  const codes = ref<string[]>([...DEFAULT_FOLLOWED_CODES])

  function reset(): void {
    codes.value = [...DEFAULT_FOLLOWED_CODES]
  }

  return { codes, reset }
})
