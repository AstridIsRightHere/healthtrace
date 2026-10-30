<script setup lang="ts">
// 首页仪表盘（FR-012）：问候 → 关注指标 → 待处理报告 → 最近就诊 → 图表入口，右下角固定上传入口
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import FollowedMetrics from '../components/home/FollowedMetrics.vue'
import PendingReportList from '../components/home/PendingReportList.vue'
import RecentVisits from '../components/home/RecentVisits.vue'
import UploadFab from '../components/home/UploadFab.vue'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'

const auth = useAuthStore()
const profile = useProfileStore()
const router = useRouter()
const loggingOut = ref(false)
const message = ref('')

async function onLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  message.value = ''
  try {
    await auth.logout()
    await router.replace({ name: 'login' })
  } catch (error) {
    // store 已在 finally 中清空本地状态；后端失败时仍提示用户
    message.value = errorMessage(error, '退出失败，请稍后重试')
    await router.replace({ name: 'login' })
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <main class="home-page home-dashboard">
    <header class="home-header home-greeting">
      <div class="home-greeting-text">
        <p class="brand">HealthTrace</p>
        <h1 data-testid="home-greeting">{{ profile.greetingTitle || '你好，今天也要好好照顾自己' }}</h1>
        <p class="field-hint home-account">{{ auth.user?.email }}</p>
      </div>
      <div class="home-header-actions">
        <RouterLink class="btn-ghost btn-small" :to="{ name: 'settings' }" data-testid="settings-link">设置</RouterLink>
        <button class="btn-secondary btn-small" type="button" :disabled="loggingOut" data-testid="logout" @click="onLogout">
          {{ loggingOut ? '退出中…' : '退出' }}
        </button>
      </div>
    </header>

    <!-- 未验证邮箱可建档，但后续上传接口会拦截（US-001），这里提前提示 -->
    <p v-if="auth.user && !auth.user.emailVerified" class="notice" role="status">
      邮箱尚未验证：验证后才能上传检查报告。
    </p>
    <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>

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
