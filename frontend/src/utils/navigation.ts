import type { RouteLocationNormalizedLoaded, RouteRecordName } from 'vue-router'

/** 主导航四个一级页面（移动端底部栏 / 桌面端侧边栏共用同一份配置） */
export type NavTab = 'home' | 'records' | 'timeline' | 'me'

export interface NavItem {
  tab: NavTab
  /** 点击后去往的路由名 */
  routeName: RouteRecordName
  label: string
}

export const NAV_ITEMS: readonly NavItem[] = [
  { tab: 'home', routeName: 'home', label: '首页' },
  { tab: 'records', routeName: 'records', label: '档案' },
  { tab: 'timeline', routeName: 'timeline', label: '时间线' },
  { tab: 'me', routeName: 'me', label: '我的' },
]

const TABS = new Set<string>(NAV_ITEMS.map((item) => item.tab))

/**
 * 当前路由归属的一级 Tab：取最内层带 meta.tab 的路由记录。
 * 二级页面（趋势、对比、就诊、回收站、设置）在路由表里声明所属 Tab，
 * 进入二级页面时对应 Tab 仍保持高亮；无法归类时返回 null（不高亮任何项）。
 */
export function activeTab(route: Pick<RouteLocationNormalizedLoaded, 'matched'>): NavTab | null {
  for (let i = route.matched.length - 1; i >= 0; i--) {
    const tab = route.matched[i]?.meta.tab
    if (typeof tab === 'string' && TABS.has(tab)) return tab as NavTab
  }
  return null
}
