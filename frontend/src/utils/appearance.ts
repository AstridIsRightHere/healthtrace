// NFR-006 外观偏好：主题（跟随系统/浅色/深色）与四档字号，仅保存在本机 localStorage，不上传服务器。
// 偏好在应用挂载前应用到 <html> 的 data-theme / data-font-size，避免首屏闪烁。

export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'
export type FontSizePreference = 'small' | 'standard' | 'large' | 'xlarge'

export const THEME_STORAGE_KEY = 'ht.theme'
export const FONT_SIZE_STORAGE_KEY = 'ht.fontSize'

export const THEME_OPTIONS: ReadonlyArray<{ value: ThemePreference; label: string }> = [
  { value: 'system', label: '跟随系统' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
]

export const FONT_SIZE_OPTIONS: ReadonlyArray<{ value: FontSizePreference; label: string; px: number }> = [
  { value: 'small', label: '小', px: 14 },
  { value: 'standard', label: '标准', px: 16 },
  { value: 'large', label: '大', px: 20 },
  { value: 'xlarge', label: '特大', px: 24 },
]

const DARK_QUERY = '(prefers-color-scheme: dark)'

export function parseTheme(value: string | null | undefined): ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
}

export function parseFontSize(value: string | null | undefined): FontSizePreference {
  return FONT_SIZE_OPTIONS.some((o) => o.value === value) ? (value as FontSizePreference) : 'standard'
}

export function resolveTheme(pref: ThemePreference, systemDark: boolean): ResolvedTheme {
  if (pref === 'system') return systemDark ? 'dark' : 'light'
  return pref
}

export function fontSizePx(pref: FontSizePreference): number {
  return FONT_SIZE_OPTIONS.find((o) => o.value === pref)?.px ?? 16
}

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    // 隐私模式等禁用存储时退回默认值
    return null
  }
}

function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // 存储不可用：本次会话内仍生效，只是不持久
  }
}

export function loadThemePreference(): ThemePreference {
  return parseTheme(readStorage(THEME_STORAGE_KEY))
}

export function loadFontSizePreference(): FontSizePreference {
  return parseFontSize(readStorage(FONT_SIZE_STORAGE_KEY))
}

function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches
}

export function applyTheme(pref: ThemePreference): ResolvedTheme {
  const resolved = resolveTheme(pref, systemPrefersDark())
  const root = document.documentElement
  root.dataset.theme = resolved
  root.dataset.themePreference = pref
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', resolved === 'dark' ? '#1c1916' : '#fdf8f3')
  return resolved
}

export function applyFontSize(pref: FontSizePreference): void {
  document.documentElement.dataset.fontSize = pref
}

export function saveThemePreference(pref: ThemePreference): ResolvedTheme {
  writeStorage(THEME_STORAGE_KEY, pref)
  return applyTheme(pref)
}

export function saveFontSizePreference(pref: FontSizePreference): void {
  writeStorage(FONT_SIZE_STORAGE_KEY, pref)
  applyFontSize(pref)
}

/** 启动时调用：应用已保存偏好，并在“跟随系统”时监听系统主题变化。 */
export function initAppearance(): void {
  applyTheme(loadThemePreference())
  applyFontSize(loadFontSizePreference())
  if (typeof window.matchMedia !== 'function') return
  const mq = window.matchMedia(DARK_QUERY)
  const onChange = () => {
    if (loadThemePreference() === 'system') applyTheme('system')
  }
  if (typeof mq.addEventListener === 'function') mq.addEventListener('change', onChange)
}
