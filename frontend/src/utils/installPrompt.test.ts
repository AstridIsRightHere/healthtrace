import { describe, expect, it } from 'vitest'
import { INSTALL_DISMISS_DAYS, installHint, isIosSafari, parseDismissedAt } from './installPrompt'

const ANDROID = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36'
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1'
const IPHONE_CHROME = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 CriOS/130 Mobile/15E148 Safari/604.1'
const now = 1_800_000_000_000

describe('installPrompt', () => {
  it('识别 iOS Safari，排除 iOS 上的其他浏览器', () => {
    expect(isIosSafari(IPHONE)).toBe(true)
    expect(isIosSafari(IPHONE_CHROME)).toBe(false)
    expect(isIosSafari(ANDROID)).toBe(false)
  })

  it('浏览器支持安装事件时提示安装，iOS Safari 提示手动添加', () => {
    expect(installHint({ standalone: false, canPrompt: true, userAgent: ANDROID, dismissedAt: null, now })).toBe('prompt')
    expect(installHint({ standalone: false, canPrompt: false, userAgent: IPHONE, dismissedAt: null, now })).toBe('ios')
    expect(installHint({ standalone: false, canPrompt: false, userAgent: ANDROID, dismissedAt: null, now })).toBe('none')
  })

  it('已安装或近期关闭过提示时不再提示', () => {
    expect(installHint({ standalone: true, canPrompt: true, userAgent: ANDROID, dismissedAt: null, now })).toBe('none')
    const recent = now - 86_400_000
    expect(installHint({ standalone: false, canPrompt: true, userAgent: ANDROID, dismissedAt: recent, now })).toBe('none')
    const old = now - (INSTALL_DISMISS_DAYS + 1) * 86_400_000
    expect(installHint({ standalone: false, canPrompt: true, userAgent: ANDROID, dismissedAt: old, now })).toBe('prompt')
  })

  it('解析关闭时间，非法值视为未关闭', () => {
    expect(parseDismissedAt(null)).toBeNull()
    expect(parseDismissedAt('abc')).toBeNull()
    expect(parseDismissedAt('123')).toBe(123)
  })
})
