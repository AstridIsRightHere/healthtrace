import { describe, expect, it } from 'vitest'
import { safeRedirect } from './redirect'

describe('safeRedirect', () => {
  it('接受站内相对路径', () => {
    expect(safeRedirect('/trends')).toBe('/trends')
    expect(safeRedirect('/reports/1/review?x=1')).toBe('/reports/1/review?x=1')
  })

  it('多值查询参数只取第一个', () => {
    expect(safeRedirect(['/trends', '/other'])).toBe('/trends')
  })

  it('拒绝外部地址与协议相对地址，回退首页', () => {
    expect(safeRedirect('https://evil.example')).toBe('/')
    expect(safeRedirect('//evil.example')).toBe('/')
    expect(safeRedirect('/\\evil.example')).toBe('/')
    expect(safeRedirect('javascript:alert(1)')).toBe('/')
  })

  it('空值回退首页', () => {
    expect(safeRedirect(undefined)).toBe('/')
    expect(safeRedirect(null)).toBe('/')
    expect(safeRedirect([])).toBe('/')
  })
})
