import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { activeTab, NAV_ITEMS } from './navigation'

function routeWith(...tabs: (string | undefined)[]): Pick<RouteLocationNormalizedLoaded, 'matched'> {
  return {
    matched: tabs.map((tab) => ({ meta: tab === undefined ? {} : { tab } })),
  } as unknown as Pick<RouteLocationNormalizedLoaded, 'matched'>
}

describe('NAV_ITEMS', () => {
  it('按 首页/档案/时间线/我的 顺序提供四个一级入口', () => {
    expect(NAV_ITEMS.map((i) => i.label)).toEqual(['首页', '档案', '时间线', '我的'])
    expect(NAV_ITEMS.map((i) => i.routeName)).toEqual(['home', 'records', 'timeline', 'me'])
  })
})

describe('activeTab', () => {
  it('一级页面高亮自身 Tab', () => {
    expect(activeTab(routeWith(undefined, 'records'))).toBe('records')
  })

  it('二级页面沿用所属 Tab（取最内层声明）', () => {
    expect(activeTab(routeWith('home', 'timeline'))).toBe('timeline')
  })

  it('无 meta.tab 或未知值时不高亮任何项', () => {
    expect(activeTab(routeWith(undefined))).toBeNull()
    expect(activeTab(routeWith('unknown'))).toBeNull()
    expect(activeTab(routeWith())).toBeNull()
  })
})
