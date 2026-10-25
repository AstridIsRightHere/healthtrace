<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage, fieldErrors } from '../api'
import { useAuthStore } from '../stores/auth'
import { safeRedirect } from '../utils/redirect'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ email: '', password: '' })
const errors = ref<Record<string, string>>({})
const message = ref('')
const submitting = ref(false)

async function onSubmit() {
  if (submitting.value) return
  submitting.value = true
  errors.value = {}
  message.value = ''
  try {
    // 登录成功后 store 会请求 /accounts/me 并写入用户信息
    await auth.login({ email: form.email.trim(), password: form.password })
    await router.replace(safeRedirect(route.query.redirect))
  } catch (error) {
    errors.value = fieldErrors(error)
    message.value = errorMessage(error, '登录失败，请稍后重试')
  } finally {
    // 无论成功与否都不在内存中保留明文密码
    form.password = ''
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card" aria-labelledby="login-title">
      <p class="brand">HealthTrace</p>
      <h1 id="login-title">欢迎回来</h1>
      <p class="subtitle">登录后继续记录你的健康点滴</p>

      <form novalidate @submit.prevent="onSubmit">
        <div class="field">
          <label for="login-email">邮箱</label>
          <input
            id="login-email"
            v-model="form.email"
            type="email"
            autocomplete="username"
            required
            :aria-invalid="!!errors.email"
            :aria-describedby="errors.email ? 'login-email-error' : undefined"
          />
          <p v-if="errors.email" id="login-email-error" class="field-error">{{ errors.email }}</p>
        </div>

        <div class="field">
          <label for="login-password">密码</label>
          <input
            id="login-password"
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            required
            :aria-invalid="!!errors.password"
            :aria-describedby="errors.password ? 'login-password-error' : undefined"
          />
          <p v-if="errors.password" id="login-password-error" class="field-error">{{ errors.password }}</p>
        </div>

        <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>

        <button class="btn-primary" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="switch">还没有账号？<RouterLink to="/register">使用邀请码注册</RouterLink></p>
    </section>
  </main>
</template>
