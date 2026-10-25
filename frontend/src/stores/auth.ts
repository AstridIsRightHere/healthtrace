import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { accountApi, type AccountView, type LoginPayload } from '../api/account'
import { resetCsrfToken } from '../api'
import { useProfileStore } from './profile'

/**
 * 登录用户状态。只在内存中保存展示所需的账号信息，不持久化（ADR-010）；
 * 真正的登录凭证是 HttpOnly 会话 Cookie，前端无法也无需读取。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<AccountView | null>(null)
  /** 本次页面生命周期内是否已向后端探测过会话（刷新页面后需重新探测） */
  const sessionChecked = ref(false)
  let pendingCheck: Promise<void> | null = null

  const isAuthenticated = computed(() => user.value !== null)

  async function login(payload: LoginPayload): Promise<void> {
    await accountApi.login(payload)
    // 可能切换了账号：丢弃上一个账号的档案缓存，由路由守卫重新获取
    useProfileStore().reset()
    // 后端登录时轮换会话并作废匿名阶段的 CSRF 令牌
    resetCsrfToken()
    user.value = await accountApi.me()
    sessionChecked.value = true
  }

  /** 用已有会话 Cookie 恢复用户信息；无会话时保持未登录 */
  function restoreSession(): Promise<void> {
    if (sessionChecked.value) return Promise.resolve()
    pendingCheck ??= accountApi
      .me()
      .then((account) => {
        user.value = account
      })
      .catch(() => {
        user.value = null
      })
      .finally(() => {
        sessionChecked.value = true
        pendingCheck = null
      })
    return pendingCheck
  }

  async function logout(): Promise<void> {
    try {
      await accountApi.logout()
    } finally {
      clear()
    }
  }

  function clear(): void {
    user.value = null
    sessionChecked.value = true
    resetCsrfToken()
    useProfileStore().reset()
  }

  return { user, isAuthenticated, login, restoreSession, logout, clear }
})
