import { api } from './index'

export type AccountStatus = 'UNVERIFIED' | 'ACTIVE' | 'FROZEN' | 'CLOSING' | 'CLOSED'

/** 对应后端 AccountView */
export interface AccountView {
  id: number
  email: string
  emailVerified: boolean
  status: AccountStatus
}

export interface RegisterPayload {
  invitationCode: string
  email: string
  password: string
}

export interface LoginPayload {
  email: string
  password: string
}

export const accountApi = {
  /** POST /api/v1/accounts：凭邀请码开通账号（US-001） */
  register: (payload: RegisterPayload) =>
    api.post<AccountView>('/accounts', payload).then((res) => res.data),

  /** POST /api/v1/email-verifications/confirmations：确认邮箱验证令牌 */
  confirmEmail: (token: string) =>
    api.post<void>('/email-verifications/confirmations', { token }).then(() => undefined),

  /** POST /api/v1/sessions：登录（US-002），成功后后端下发 HttpOnly 会话 Cookie */
  login: (payload: LoginPayload) =>
    api.post<AccountView>('/sessions', payload).then((res) => res.data),

  /** DELETE /api/v1/sessions/current：退出 */
  logout: () => api.delete<void>('/sessions/current').then(() => undefined),

  /**
   * POST /api/v1/account-closure：注销账号（FR-017）。需再次输入密码；
   * 成功 204 后后端已删除账号与全部档案数据并作废会话，调用方按已退出处理。
   */
  close: (password: string) =>
    api.post<void>('/account-closure', { password }).then(() => undefined),

  /** GET /api/v1/accounts/me：当前账号；401 由调用方处理，不触发全局跳转 */
  me: () =>
    api.get<AccountView>('/accounts/me', { skipAuthRedirect: true }).then((res) => res.data),
}
