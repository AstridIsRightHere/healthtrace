import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'

declare module 'vue-router' {
  interface RouteMeta {
    /** 未登录也可访问 */
    public?: boolean
    /** 已登录用户访问时直接回到首页（登录、注册页） */
    guestOnly?: boolean
    /** 登录后即可访问，不要求已建档（建档页） */
    allowWithoutProfile?: boolean
    /** 所属一级导航 Tab（见 utils/navigation.ts），用于底部栏/侧边栏高亮 */
    tab?: 'home' | 'records' | 'timeline' | 'me'
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      // 布局路由：四个一级 Tab 及其二级页面共用 AppLayout（底部栏 / 侧边栏）。
      // meta.tab 声明所属 Tab，用于导航高亮；上传与核对是专注流程，不在布局内。
      path: '/',
      component: () => import('../components/layout/AppLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('../views/HomeView.vue'), meta: { tab: 'home' } },
        { path: 'records', name: 'records', component: () => import('../views/RecordsView.vue'), meta: { tab: 'records' } },
        { path: 'timeline', name: 'timeline', component: () => import('../views/TimelineView.vue'), meta: { tab: 'timeline' } },
        { path: 'me', name: 'me', component: () => import('../views/ProfileView.vue'), meta: { tab: 'me' } },
        { path: 'trends', name: 'trends', component: () => import('../views/TrendView.vue'), meta: { tab: 'home' } },
        {
          path: 'reports/compare',
          name: 'report-compare',
          component: () => import('../views/ReportCompareView.vue'),
          meta: { tab: 'records' },
        },
        { path: 'visits', name: 'visits', component: () => import('../views/VisitView.vue'), meta: { tab: 'timeline' } },
        { path: 'recycle-bin', name: 'recycle-bin', component: () => import('../views/RecycleBinView.vue'), meta: { tab: 'me' } },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('../views/SettingsView.vue'),
          // 未建档的账号同样可以注销（FR-017）
          meta: { tab: 'me', allowWithoutProfile: true },
        },
      ],
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, guestOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue'),
      meta: { public: true, guestOnly: true },
    },
    {
      path: '/verify',
      name: 'verify',
      component: () => import('../views/VerifyView.vue'),
      meta: { public: true },
    },
    {
      path: '/profile/new',
      name: 'profile-create',
      component: () => import('../views/ProfileCreateView.vue'),
      meta: { allowWithoutProfile: true },
    },
    {
      path: '/reports/new',
      name: 'report-upload',
      component: () => import('../views/ReportUploadView.vue'),
    },
    {
      path: '/reports/:reportId(\\d+)/review',
      name: 'report-review',
      component: () => import('../views/ReportReviewView.vue'),
      props: (route) => ({ reportId: Number(route.params.reportId) }),
    },
    // 后端 dev 邮件日志中的链接为 /verify-email?token=...，保留查询参数转到 /verify
    { path: '/verify-email', redirect: (to) => ({ name: 'verify', query: to.query }) },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/**
 * 路由守卫（US-003）：
 * 1. 未登录只能访问 public 页面（/login、/register、/verify）；
 * 2. 已登录但未建档：除 public 页外一律引导到 /profile/new；
 * 3. 已建档再访问 /profile/new 回到首页。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  // 刷新页面后 Pinia 为空：先用 HttpOnly 会话 Cookie 向后端探测一次登录态
  await auth.restoreSession()

  if (!to.meta.public && !auth.isAuthenticated) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.isAuthenticated) {
    return { name: 'home' }
  }
  if (to.meta.public) return true

  const profile = useProfileStore(pinia)
  try {
    await profile.load()
  } catch {
    // 网络等异常：不强行跳转，放行到目标页，由页面或下次导航重试
    return true
  }
  if (!profile.hasProfile && !to.meta.allowWithoutProfile) {
    return { name: 'profile-create' }
  }
  if (profile.hasProfile && to.name === 'profile-create') {
    return { name: 'home' }
  }
  return true
})

export default router
