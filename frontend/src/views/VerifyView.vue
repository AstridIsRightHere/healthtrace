<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { accountApi } from '../api/account'
import { errorMessage } from '../api'

type State = 'pending' | 'success' | 'error'

const REDIRECT_DELAY_MS = 2500

const route = useRoute()
const router = useRouter()
const state = ref<State>('pending')
const message = ref('正在验证你的邮箱…')
let timer: ReturnType<typeof setTimeout> | undefined

onMounted(async () => {
  const raw = route.query.token
  const token = (Array.isArray(raw) ? raw[0] : raw)?.trim()
  // 令牌只用于这一次请求：立即从地址栏移除，避免留在浏览历史或被截图带出
  void router.replace({ name: 'verify' })

  if (!token) {
    state.value = 'error'
    message.value = '验证链接无效，请检查链接是否完整'
    return
  }
  try {
    await accountApi.confirmEmail(token)
    state.value = 'success'
    message.value = '验证成功，请登录'
    timer = setTimeout(() => void router.replace({ name: 'login' }), REDIRECT_DELAY_MS)
  } catch (error) {
    state.value = 'error'
    message.value = errorMessage(error, '验证失败，请稍后重试')
  }
})

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <main class="auth-page">
    <section class="auth-card" aria-labelledby="verify-title">
      <p class="brand">HealthTrace</p>
      <h1 id="verify-title">邮箱验证</h1>

      <p
        class="notice"
        :class="{ 'notice-success': state === 'success', 'notice-error': state === 'error' }"
        :role="state === 'error' ? 'alert' : 'status'"
      >
        {{ message }}
      </p>

      <p v-if="state === 'success'" class="subtitle">稍后将自动跳转到登录页…</p>
      <RouterLink v-if="state !== 'pending'" class="btn-primary btn-link" to="/login">前往登录</RouterLink>
    </section>
  </main>
</template>
