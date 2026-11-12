import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  FONT_SIZE_STORAGE_KEY,
  THEME_STORAGE_KEY,
  fontSizePx,
  initAppearance,
  parseFontSize,
  parseTheme,
  resolveTheme,
  saveFontSizePreference,
  saveThemePreference,
} from './appearance'

function mockMatchMedia(dark: boolean) {
  const listeners: Array<() => void> = []
  const mq = {
    matches: dark,
    addEventListener: (_: string, cb: () => void) => listeners.push(cb),
  }
  vi.stubGlobal('matchMedia', vi.fn(() => mq))
  window.matchMedia = globalThis.matchMedia
  return {
    setDark(v: boolean) {
      mq.matches = v
      listeners.forEach((cb) => cb())
    },
  }
}

describe('appearance', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
    delete document.documentElement.dataset.fontSize
  })
  afterEach(() => vi.unstubAllGlobals())

  it('解析非法值时回退到默认', () => {
    expect(parseTheme('purple')).toBe('system')
    expect(parseTheme(null)).toBe('system')
    expect(parseTheme('dark')).toBe('dark')
    expect(parseFontSize('huge')).toBe('standard')
    expect(parseFontSize('xlarge')).toBe('xlarge')
  })

  it('跟随系统时按系统偏好解析，手动选择优先', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('四档字号对应 14/16/20/24px', () => {
    expect(['small', 'standard', 'large', 'xlarge'].map((v) => fontSizePx(parseFontSize(v)))).toEqual([14, 16, 20, 24])
  })

  it('保存偏好写入 localStorage 并更新根元素', () => {
    mockMatchMedia(false)
    saveThemePreference('dark')
    saveFontSizePreference('large')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(localStorage.getItem(FONT_SIZE_STORAGE_KEY)).toBe('large')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.dataset.fontSize).toBe('large')
  })

  it('初始化读取已存偏好，跟随系统时响应系统切换', () => {
    const media = mockMatchMedia(false)
    localStorage.setItem(FONT_SIZE_STORAGE_KEY, 'xlarge')
    initAppearance()
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.dataset.fontSize).toBe('xlarge')
    media.setDark(true)
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('手动选择后不再随系统变化', () => {
    const media = mockMatchMedia(false)
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    initAppearance()
    media.setDark(true)
    expect(document.documentElement.dataset.theme).toBe('light')
  })
})
