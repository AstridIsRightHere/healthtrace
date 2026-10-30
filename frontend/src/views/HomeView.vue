<script setup lang="ts">
// 首页仪表盘（FR-012）：问候 → 关注指标 → 待处理报告 → 最近就诊 → 图表入口。
// 退出登录、回收站、账号设置已迁移到“我的”Tab；上传入口在移动端为悬浮按钮（位于底部导航之上），
// 桌面端由侧边栏“上传报告”承担。
import { RouterLink } from 'vue-router'
import FollowedMetrics from '../components/home/FollowedMetrics.vue'
import PendingReportList from '../components/home/PendingReportList.vue'
import RecentVisits from '../components/home/RecentVisits.vue'
import UploadFab from '../components/home/UploadFab.vue'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'

const auth = useAuthStore()
const profile = useProfileStore()
</script>

<template>
  <main class="home-page home-dashboard">
    <header class="home-header home-greeting">
      <div class="home-greeting-text">
        <p class="brand">HealthTrace</p>
        <h1 data-testid="home-greeting">{{ profile.greetingTitle || '你好，今天也要好好照顾自己' }}</h1>
        <p class="field-hint home-account">{{ auth.user?.email }}</p>
      </div>
    </header>

    <!-- 未验证邮箱可建档，但后续上传接口会拦截（US-001），这里提前提示 -->
    <p v-if="auth.user && !auth.user.emailVerified" class="notice" role="status">
      邮箱尚未验证：验证后才能上传检查报告。
    </p>

    <FollowedMetrics v-if="profile.profile" :profile-id="profile.profile.id" />

    <PendingReportList v-if="profile.profile" :profile-id="profile.profile.id" />

    <RecentVisits v-if="profile.profile" :profile-id="profile.profile.id" />

    <section class="home-card trend-entry" aria-labelledby="trend-entry-title">
      <div>
        <h2 id="trend-entry-title">指标趋势</h2>
        <p class="field-hint">查看已确认结果随时间的变化，图表下方附完整数据表格。</p>
      </div>
      <div class="trend-entry-actions">
        <RouterLink class="btn-secondary btn-small" :to="{ name: 'trends' }">查看趋势</RouterLink>
        <RouterLink class="btn-ghost btn-small" :to="{ name: 'report-compare' }">报告对比</RouterLink>
      </div>
    </section>
    <UploadFab />
  </main>
</template>
