<script setup lang="ts">
// 首页：按档案关系展示问候标题（PRD），指标等业务内容见 FR-012，后续迭代实现
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../api'
import PendingReportList from '../components/home/PendingReportList.vue'
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
  <main class="home-page">
    <header class="home-header">
      <p class="brand">HealthTrace</p>
      <button class="btn-secondary" type="button" :disabled="loggingOut" @click="onLogout">
        {{ loggingOut ? '退出中…' : '退出' }}
      </button>
    </header>

    <section class="home-card">
      <h1>{{ profile.greetingTitle || '你好，今天也要好好照顾自己' }}</h1>
      <p>当前登录用户：<strong>{{ auth.user?.email }}</strong></p>
      <!-- 未验证邮箱可建档，但后续上传接口会拦截（US-001），这里提前提示 -->
      <p v-if="auth.user && !auth.user.emailVerified" class="notice" role="status">
        邮箱尚未验证：验证后才能上传检查报告。
      </p>
      <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
    </section>

    <PendingReportList v-if="profile.profile" :profile-id="profile.profile.id" />
    <UploadFab />
  </main>
</template>
