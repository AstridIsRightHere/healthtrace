import { createRouter, createWebHistory } from 'vue-router'

// 骨架阶段仅保留首页占位路由，业务页面按 07-页面清单 逐步补充
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../views/HomeView.vue'),
    },
  ],
})

export default router
