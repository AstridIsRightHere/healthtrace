<script setup lang="ts">
// 账号设置：注销账号（FR-017）。需输入密码 + 二次确认；成功后账号、档案与全部数据被永久删除并退出登录。
import { nextTick, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { errorMessage, fieldErrors } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const password = ref('')
const errors = ref<Record<string, string>>({})
const message = ref('')
const showConfirm = ref(false)
const closing = ref(false)
const dialog = ref<HTMLDialogElement | null>(null)

watch(showConfirm, async (open) => {
  if (!open) return
  await nextTick()
  dialog.value?.showModal()
})

function openConfirm() {
  errors.value = {}
  message.value = ''
  if (!password.value) {
    errors.value = { password: '请输入当前密码' }
    return
  }
  showConfirm.value = true
}

function cancelConfirm() {
  if (closing.value) return
  dialog.value?.close()
  showConfirm.value = false
}

async function confirmClose() {
  if (closing.value) return
  closing.value = true
  message.value = ''
  try {
    await auth.closeAccount(password.value)
    password.value = ''
    showConfirm.value = false
    await router.replace({ name: 'login', query: { closed: '1' } })
  } catch (error) {
    dialog.value?.close()
    showConfirm.value = false
    errors.value = fieldErrors(error)
    message.value = errorMessage(error, '注销失败，请稍后重试')
    password.value = ''
  } finally {
    closing.value = false
  }
}
</script>

<template>
  <main class="home-page settings-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'me' }">← 返回我的</RouterLink>
      <p class="brand">账号设置</p>
    </header>

    <section class="home-card" aria-labelledby="account-title">
      <h2 id="account-title">当前账号</h2>
      <p class="field-hint" data-testid="settings-email">{{ auth.user?.email }}</p>
    </section>

    <section class="home-card danger-zone" aria-labelledby="closure-title" data-testid="closure-zone">
      <h2 id="closure-title">注销账号</h2>
      <p class="field-hint">
        注销后，账号、健康档案、全部报告原件与分析数据、就诊记录和关注设置将被<strong>永久删除</strong>，无法恢复，也不能再用此邮箱登录原账号。
      </p>
      <form novalidate @submit.prevent="openConfirm">
        <div class="field">
          <label for="closure-password">当前密码</label>
          <input
            id="closure-password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
            data-testid="closure-password"
            :aria-invalid="!!errors.password"
            :aria-describedby="errors.password ? 'closure-password-error' : undefined"
          />
          <p v-if="errors.password" id="closure-password-error" class="field-error">{{ errors.password }}</p>
        </div>
        <p v-if="message" class="notice notice-error" role="alert" data-testid="closure-error">{{ message }}</p>
        <button class="btn-danger-outline btn-small" type="submit" :disabled="closing" data-testid="closure-open">
          注销账号
        </button>
      </form>
    </section>

    <dialog
      v-if="showConfirm"
      ref="dialog"
      class="dup-dialog"
      aria-labelledby="closure-confirm-title"
      aria-describedby="closure-confirm-desc"
      data-testid="closure-dialog"
      @cancel.prevent="cancelConfirm"
    >
      <h2 id="closure-confirm-title">确认永久注销账号？</h2>
      <p id="closure-confirm-desc">
        账号 {{ auth.user?.email }} 及其<strong>原件和所有分析数据将被永久移除</strong>。此操作无法撤销。
      </p>
      <div class="dup-actions">
        <button class="btn-secondary" type="button" :disabled="closing" @click="cancelConfirm">取消</button>
        <button
          class="btn-primary btn-danger"
          type="button"
          :disabled="closing"
          data-testid="closure-confirm"
          @click="confirmClose"
        >
          {{ closing ? '注销中…' : '永久注销' }}
        </button>
      </div>
    </dialog>
  </main>
</template>
