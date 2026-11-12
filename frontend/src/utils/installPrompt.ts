// PWA 安装提示的纯逻辑（便于单元测试）。

export const INSTALL_DISMISS_KEY = 'ht.pwaInstallDismissedAt'
/** 关闭安装提示后，30 天内不再主动提示。 */
export const INSTALL_DISMISS_DAYS = 30

export type InstallHint = 'none' | 'prompt' | 'ios'

export interface InstallEnv {
  /** 已作为独立应用（主屏幕）打开 */
  standalone: boolean
  /** 浏览器已触发 beforeinstallprompt（Chrome/Edge/Android） */
  canPrompt: boolean
  userAgent: string
  /** 上次关闭提示的时间戳（毫秒），没有则为 null */
  dismissedAt: number | null
  now: number
}

/** iOS / iPadOS 的 Safari 不支持 beforeinstallprompt，只能提示用户手动“添加到主屏幕”。 */
export function isIosSafari(ua: string): boolean {
  const ios = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && /Mobile/i.test(ua))
  const otherBrowser = /CriOS|FxiOS|EdgiOS/i.test(ua)
  return ios && !otherBrowser
}

export function installHint(env: InstallEnv): InstallHint {
  if (env.standalone) return 'none'
  if (env.dismissedAt !== null && env.now - env.dismissedAt < INSTALL_DISMISS_DAYS * 86_400_000) return 'none'
  if (env.canPrompt) return 'prompt'
  if (isIosSafari(env.userAgent)) return 'ios'
  return 'none'
}

export function parseDismissedAt(value: string | null): number | null {
  if (!value) return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
}
