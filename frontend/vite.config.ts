import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // PWA：只预缓存构建产物（页面程序、样式、图标），不对任何接口做运行时缓存。
    // 安全红线：/api 下的报告、照片、检查数值等医疗数据绝不进入 Service Worker 缓存（见 docs/17、NFR-002）。
    VitePWA({
      registerType: 'prompt',
      injectRegister: false,
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'HealthTrace 家庭检验报告管理',
        short_name: 'HealthTrace',
        description: '整理检验报告、查看指标趋势与就诊时间线',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#fdf8f3',
        theme_color: '#e07a5f',
        icons: [
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: 'pwa-maskable-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest}'],
        // ECharts 分包较大（约 540 kB），需提高单文件预缓存上限
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
        navigateFallback: 'index.html',
        // 接口请求与 Swagger 文档不走 SPA 回退
        navigateFallbackDenylist: [/^\/api\//, /^\/swagger-ui/, /^\/v3\/api-docs/],
        // 不配置 runtimeCaching：未预缓存的请求（含全部 /api）直接走网络，不写入任何缓存
        runtimeCaching: [],
        cleanupOutdatedCaches: true,
      },
      devOptions: { enabled: false },
    }),
  ],
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
  // 本地 `vite preview` 验证生产构建（含 Service Worker、manifest）时同样转发 /api
  preview: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  // Vitest：组件测试需要 DOM 环境；只收集 src 下的 *.test.ts
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
