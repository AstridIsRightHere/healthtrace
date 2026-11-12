import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { pinia } from './stores'
import { useAuthStore } from './stores/auth'
import { setUnauthorizedHandler } from './api'
import { initAppearance } from './utils/appearance'
import { registerPwa } from './utils/pwa'

// NFR-006：挂载前应用本机保存的主题与字号
initAppearance()

// 任一业务请求返回 401（未登录/会话过期）：清空内存中的用户状态并回到登录页
setUnauthorizedHandler(() => {
  useAuthStore(pinia).clear()
  const current = router.currentRoute.value
  if (!current.meta.public) {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

createApp(App).use(pinia).use(router).mount('#app')

registerPwa()
