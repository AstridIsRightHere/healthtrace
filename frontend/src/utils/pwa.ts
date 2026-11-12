// PWA 注册与安装/更新状态。Service Worker 只预缓存静态构建产物，不缓存任何 /api 数据（见 vite.config.ts）。
import { reactive } from 'vue'
import { registerSW } from 'virtual:pwa-register'
import { INSTALL_DISMISS_KEY, installHint, parseDismissedAt, type InstallHint } from './installPrompt'

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export const pwaState = reactive({
  hint: 'none' as InstallHint,
  needRefresh: false,
})

let deferredPrompt: BeforeInstallPromptEvent | null = null
let updateSW: ((reload?: boolean) => Promise<void>) | null = null

function readDismissedAt(): number | null {
  try {
    return parseDismissedAt(window.localStorage.getItem(INSTALL_DISMISS_KEY))
  } catch {
    return null
  }
}

function isStandalone(): boolean {
  const nav = navigator as Navigator & { standalone?: boolean }
  return (typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches)
    || nav.standalone === true
}

function refreshHint(): void {
  pwaState.hint = installHint({
    standalone: isStandalone(),
    canPrompt: deferredPrompt !== null,
    userAgent: navigator.userAgent,
    dismissedAt: readDismissedAt(),
    now: Date.now(),
  })
}

export async function promptInstall(): Promise<void> {
  if (!deferredPrompt) return
  const event = deferredPrompt
  deferredPrompt = null
  await event.prompt()
  await event.userChoice.catch(() => undefined)
  refreshHint()
}

export function dismissInstall(): void {
  try {
    window.localStorage.setItem(INSTALL_DISMISS_KEY, String(Date.now()))
  } catch {
    // 存储不可用：仅本次关闭
  }
  pwaState.hint = 'none'
}

export async function applyUpdate(): Promise<void> {
  pwaState.needRefresh = false
  await updateSW?.(true)
}

export function dismissUpdate(): void {
  pwaState.needRefresh = false
}

export function registerPwa(): void {
  window.addEventListener('beforeinstallprompt', (e) => {
    // 阻止浏览器默认迷你横条，改为在页面内展示可关闭的提示
    e.preventDefault()
    deferredPrompt = e as BeforeInstallPromptEvent
    refreshHint()
  })
  window.addEventListener('appinstalled', () => {
    deferredPrompt = null
    pwaState.hint = 'none'
  })
  refreshHint()
  if (!('serviceWorker' in navigator)) return
  updateSW = registerSW({
    onNeedRefresh() {
      pwaState.needRefresh = true
    },
  })
}
