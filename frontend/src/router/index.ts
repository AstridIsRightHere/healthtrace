import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 未登录也可访问 */
    public?: boolean
    /** 已登录用户访问时直接回到首页（登录、注册页） */
    guestOnly?: boolean
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
    // 后端 dev 邮件日志中的链接为 /verify-email?token=...，保留查询参数转到 /verify
    { path: '/verify-email', redirect: (to) => ({ name: 'verify', query: to.query }) },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 路由守卫：未登录用户只能访问 public 页面（/login、/register、/verify）
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
  return true
})

export default router
