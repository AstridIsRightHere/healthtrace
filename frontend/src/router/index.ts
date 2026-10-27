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
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../views/HomeView.vue'),
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
    {
      path: '/trends',
      name: 'trends',
      component: () => import('../views/TrendView.vue'),
    },
    {
      path: '/reports/compare',
      name: 'report-compare',
      component: () => import('../views/ReportCompareView.vue'),
    },
    {
      path: '/visits',
      name: 'visits',
      component: () => import('../views/VisitView.vue'),
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
