<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { accountApi } from '../api/account'
import { errorMessage, fieldErrors } from '../api'

// 与后端 AuthPolicy 一致（DEC-017）：至少 12 个字符，不做复杂度要求
const PASSWORD_MIN_LENGTH = 12

const form = reactive({ invitationCode: '', email: '', password: '' })
const errors = ref<Record<string, string>>({})
const message = ref('')
const registered = ref(false)
const submitting = ref(false)

const passwordLength = computed(() => Array.from(form.password).length)

function validate(): boolean {
  const result: Record<string, string> = {}
  if (!form.invitationCode.trim()) result.invitationCode = '请输入邀请码'
  if (!form.email.trim()) result.email = '请输入邮箱'
  if (passwordLength.value < PASSWORD_MIN_LENGTH) result.password = `密码至少需要 ${PASSWORD_MIN_LENGTH} 个字符`
  errors.value = result
  return Object.keys(result).length === 0
}

async function onSubmit() {
  if (submitting.value) return
  message.value = ''
  if (!validate()) return
  submitting.value = true
  try {
    await accountApi.register({
      invitationCode: form.invitationCode.trim(),
      email: form.email.trim(),
      password: form.password,
    })
    registered.value = true
    form.password = ''
  } catch (error) {
    errors.value = fieldErrors(error)
    message.value = errorMessage(error, '注册失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card" aria-labelledby="register-title">
      <p class="brand">HealthTrace</p>

      <template v-if="registered">
        <h1 id="register-title">注册成功</h1>
        <p class="notice notice-success" role="status">请去后台日志查收验证链接</p>
        <p class="subtitle">打开链接完成邮箱验证后，就可以登录啦。</p>
        <RouterLink class="btn-primary btn-link" to="/login">前往登录</RouterLink>
      </template>

      <template v-else>
        <h1 id="register-title">创建账号</h1>
        <p class="subtitle">凭邀请码开通，开始记录你的健康旅程</p>

        <form novalidate @submit.prevent="onSubmit">
          <div class="field">
            <label for="register-code">邀请码</label>
            <input
              id="register-code"
              v-model="form.invitationCode"
              type="text"
              autocomplete="off"
              required
              :aria-invalid="!!errors.invitationCode"
              :aria-describedby="errors.invitationCode ? 'register-code-error' : undefined"
            />
            <p v-if="errors.invitationCode" id="register-code-error" class="field-error">{{ errors.invitationCode }}</p>
          </div>

          <div class="field">
            <label for="register-email">邮箱</label>
            <input
              id="register-email"
              v-model="form.email"
              type="email"
              autocomplete="email"
              required
              :aria-invalid="!!errors.email"
              :aria-describedby="errors.email ? 'register-email-error' : undefined"
            />
            <p v-if="errors.email" id="register-email-error" class="field-error">{{ errors.email }}</p>
          </div>

          <div class="field">
            <label for="register-password">密码</label>
            <input
              id="register-password"
              v-model="form.password"
              type="password"
              autocomplete="new-password"
              required
              aria-describedby="register-password-hint"
              :aria-invalid="!!errors.password"
            />
            <p id="register-password-hint" :class="errors.password ? 'field-error' : 'field-hint'">
              {{ errors.password || `至少 ${PASSWORD_MIN_LENGTH} 个字符（当前 ${passwordLength}）` }}
            </p>
          </div>

          <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>

          <button class="btn-primary" type="submit" :disabled="submitting">
            {{ submitting ? '提交中…' : '注册' }}
          </button>
        </form>

        <p class="switch">已有账号？<RouterLink to="/login">直接登录</RouterLink></p>
      </template>
    </section>
  </main>
</template>
