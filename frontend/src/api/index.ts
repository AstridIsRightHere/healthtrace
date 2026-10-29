import axios, { type AxiosError } from 'axios'

/** 字段级校验错误（ProblemDetail 扩展字段 fieldErrors 的元素） */
export interface ApiFieldError {
  field: string
  /** 校验原因码，如 NotNull、NO_CHANGE（后端 JSON 键为 reasonCode） */
  reasonCode: string
  message: string
}

/**
 * 后端统一错误响应：RFC 9457 ProblemDetail（application/problem+json）。
 * 标准字段 type/title/status/detail/instance；业务码 code、traceId、fieldErrors、retryable 为扩展字段。
 */
export interface ApiErrorBody {
  type?: string
  title?: string
  status?: number
  /** 面向用户的错误说明 */
  detail?: string
  instance?: string
  /** 业务错误码，如 HT-2001 */
  code: string
  traceId?: string
  fieldErrors?: ApiFieldError[]
  retryable?: boolean
}

/** 列表接口的分页外壳：items 受 limit 限制，total 为忽略 limit 的总数 */
export interface ListPage<T> {
  items: T[]
  total: number
}

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 为 true 时 401 不触发全局跳转登录（例如启动时探测会话） */
    skipAuthRedirect?: boolean
    /** 内部使用：CSRF 失效后已重试过一次 */
    csrfRetried?: boolean
  }
}

const CSRF_HEADER = 'X-CSRF-TOKEN'
const SAFE_METHODS = new Set(['get', 'head', 'options', 'trace'])

/**
 * 统一 Axios 实例。登录态完全依赖后端下发的 HttpOnly 会话 Cookie，
 * 前端不在 localStorage / sessionStorage 中保存任何令牌（红线）。
 * 开发环境下 /api 由 Vite 代理转发到后端，浏览器视为同源请求。
 */
export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  timeout: 15_000,
  headers: { Accept: 'application/json' },
})

// CSRF 令牌只保存在内存中：刷新页面后重新获取
let csrfToken: string | null = null
let csrfPending: Promise<string> | null = null

async function ensureCsrfToken(): Promise<string> {
  if (csrfToken) return csrfToken
  // 用裸 axios 获取，避免再次进入本实例的拦截器；并发请求共享同一次获取
  csrfPending ??= axios
    .get<{ headerName: string; token: string }>('/api/v1/csrf-token', { withCredentials: true })
    .then((res) => {
      csrfToken = res.data.token
      return csrfToken
    })
    .finally(() => {
      csrfPending = null
    })
  return csrfPending
}

/** 登录、退出或会话失效后，后端会作废旧 CSRF 令牌，需丢弃缓存 */
export function resetCsrfToken(): void {
  csrfToken = null
}

let unauthorizedHandler: (() => void) | null = null

/** 由 main.ts 注入 401 处理（清空用户状态并跳转登录），避免 api ↔ router 循环依赖 */
export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

// 请求拦截器：所有会改变状态的请求自动带上 X-CSRF-TOKEN
api.interceptors.request.use(async (config) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (!SAFE_METHODS.has(method)) {
    config.headers.set(CSRF_HEADER, await ensureCsrfToken())
  }
  return config
})

// 响应拦截器：CSRF 失效自动刷新重试一次；未登录（HT-2001）跳转登录页
api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiErrorBody>) => {
    const config = error.config
    const status = error.response?.status
    const code = error.response?.data?.code

    if (config && status === 403 && code === 'HT-2005' && !config.csrfRetried) {
      resetCsrfToken()
      config.csrfRetried = true
      return api.request(config)
    }

    // 只处理"未登录/会话失效"；登录失败（HT-2002）等交给页面自行提示
    if (status === 401 && code === 'HT-2001') {
      resetCsrfToken()
      if (!config?.skipAuthRedirect) unauthorizedHandler?.()
    }
    return Promise.reject(error)
  },
)

/** 提取适合直接展示给用户的错误文案 */
export function errorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    if (!error.response) return '无法连接服务器，请检查网络后重试'
    if (error.response.data?.detail) return error.response.data.detail
  }
  return fallback
}

/** 提取字段级校验错误：{ 字段名: 提示 } */
export function fieldErrors(error: unknown): Record<string, string> {
  const result: Record<string, string> = {}
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    for (const item of error.response?.data?.fieldErrors ?? []) {
      result[item.field] ??= item.message
    }
  }
  return result
}
