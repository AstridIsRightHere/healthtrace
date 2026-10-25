import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { profileApi, type CreateProfilePayload, type ProfileView } from '../api/profile'

/**
 * 当前账号的患者档案。只保存在内存中（ADR-010），刷新页面后通过 /current 重新获取；
 * 退出或切换账号时由 auth store 调用 reset()。
 */
export const useProfileStore = defineStore('profile', () => {
  const profile = ref<ProfileView | null>(null)
  /** 是否已确认过当前账号的建档情况（true 且 profile 为 null 表示确实未建档） */
  const loaded = ref(false)
  let pending: Promise<void> | null = null

  const hasProfile = computed(() => profile.value !== null)

  /** 首页问候语（PRD）：本人“我的指标”；代管“{昵称}的指标”，无昵称“家人的指标” */
  const greetingTitle = computed(() => {
    const current = profile.value
    if (!current) return ''
    if (current.relationship === 'SELF') return '我的指标'
    return current.nickname ? `${current.nickname}的指标` : '家人的指标'
  })

  /** 获取当前档案；网络等异常向上抛出，loaded 保持 false 以便下次重试 */
  function load(): Promise<void> {
    if (loaded.value) return Promise.resolve()
    pending ??= profileApi
      .current()
      .then((result) => {
        profile.value = result
        loaded.value = true
      })
      .finally(() => {
        pending = null
      })
    return pending
  }

  async function create(payload: CreateProfilePayload): Promise<ProfileView> {
    const created = await profileApi.create(payload)
    profile.value = created
    loaded.value = true
    return created
  }

  function reset(): void {
    profile.value = null
    loaded.value = false
    pending = null
  }

  return { profile, loaded, hasProfile, greetingTitle, load, create, reset }
})
