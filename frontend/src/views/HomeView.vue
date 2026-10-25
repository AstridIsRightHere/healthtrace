<script setup lang="ts">
// 首页：目前只展示登录身份与退出入口，业务内容见 FR-012，后续迭代实现
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
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
      <h1>你好，今天也要好好照顾自己</h1>
      <p>当前登录用户：<strong>{{ auth.user?.email }}</strong></p>
      <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
    </section>
  </main>
</template>
