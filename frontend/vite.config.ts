import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // 开发期把 /api 转发到本地后端：浏览器只与 Vite 同源通信，无需 CORS，
    // 会话 Cookie 与 CSRF 令牌都落在同一来源下
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
})
