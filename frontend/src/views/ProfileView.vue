<script setup lang="ts">
// 我的（一级 Tab）：账号与档案信息，关注指标管理、回收站、账号设置、退出登录入口（从首页迁移）。
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import ManageFollowDialog from '../components/home/ManageFollowDialog.vue'
import { useAuthStore } from '../stores/auth'
import { useFollowStore } from '../stores/follow'
import { useProfileStore } from '../stores/profile'

const auth = useAuthStore()
const profile = useProfileStore()
const follow = useFollowStore()
const router = useRouter()

const relationshipText = { SELF: '本人', CHILD: '子女', SPOUSE: '配偶', OTHER: '其他家人' } as const
const profileName = computed(() => {
  const p = profile.profile
  if (!p) return ''
  return p.nickname || (p.relationship === 'SELF' ? '我' : '家人')
})

const managing = ref(false)
const opening = ref(false)
const saving = ref(false)
const saveError = ref('')
const message = ref('')
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null

async function openManage(): Promise<void> {
  const pid = profile.profile?.id
  if (!pid || opening.value) return
  opening.value = true
  message.value = ''
  saveError.value = ''
  try {
    await follow.load(pid)
    managing.value = true
  } catch (error) {
    message.value = errorMessage(error, '关注列表加载失败，请稍后重试')
  } finally {
    opening.value = false
  }
}

async function saveManage(codes: string[]): Promise<void> {
  const pid = profile.profile?.id
  if (!pid) return
  saving.value = true
  saveError.value = ''
  try {
    await follow.save(pid, codes)
    managing.value = false
    if (toastTimer) clearTimeout(toastTimer)
    toast.value = '关注指标已保存'
    toastTimer = setTimeout(() => (toast.value = ''), 3000)
  } catch {
    saveError.value = '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

const loggingOut = ref(false)
async function onLogout(): Promise<void> {
  if (loggingOut.value) return
  loggingOut.value = true
  message.value = ''
  try {
    await auth.logout()
  } catch {
    // store 已在 finally 中清空本地状态；后端失败也回到登录页
  } finally {
    loggingOut.value = false
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <main class="home-page tab-page" data-testid="me-page">
    <header class="tab-header">
      <h1>我的</h1>
    </header>

    <section class="home-card me-account" aria-labelledby="me-account-title" data-testid="me-account">
      <h2 id="me-account-title" class="visually-hidden">账号与档案</h2>
      <div class="me-avatar" aria-hidden="true">{{ profileName.slice(0, 1) || '我' }}</div>
      <div class="me-account-text">
        <p class="me-name">{{ profileName || '未建档' }}</p>
        <p v-if="profile.profile" class="field-hint">
          档案关系：{{ relationshipText[profile.profile.relationship] }}
        </p>
        <p class="field-hint home-account" data-testid="me-email">{{ auth.user?.email }}</p>
      </div>
    </section>

    <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>

    <section class="home-card me-menu-card" aria-label="功能入口">
      <ul class="me-menu">
        <li>
          <button class="me-menu-item" type="button" :disabled="opening || !profile.profile" data-testid="me-follow-manage" @click="openManage">
            <span class="me-menu-label">关注指标管理</span>
            <span class="field-hint">选择首页展示的指标并排序</span>
            <span class="me-menu-arrow" aria-hidden="true">›</span>
          </button>
        </li>
        <li>
          <RouterLink class="me-menu-item" :to="{ name: 'recycle-bin' }" data-testid="recycle-link">
            <span class="me-menu-label">回收站</span>
            <span class="field-hint">恢复或彻底删除已移除的报告</span>
            <span class="me-menu-arrow" aria-hidden="true">›</span>
          </RouterLink>
        </li>
        <li>
          <RouterLink class="me-menu-item" :to="{ name: 'settings' }" data-testid="settings-link">
            <span class="me-menu-label">账号设置</span>
            <span class="field-hint">账号信息与注销</span>
            <span class="me-menu-arrow" aria-hidden="true">›</span>
          </RouterLink>
        </li>
      </ul>
    </section>

    <button class="btn-secondary me-logout" type="button" :disabled="loggingOut" data-testid="logout" @click="onLogout">
      {{ loggingOut ? '退出中…' : '退出登录' }}
    </button>

    <ManageFollowDialog
      v-if="managing"
      :initial-codes="follow.codes"
      :saving="saving"
      :error="saveError"
      @save="saveManage"
      @cancel="managing = false"
    />

    <Transition name="toast">
      <p v-if="toast" class="toast notice-success" role="status" data-testid="me-toast">{{ toast }}</p>
    </Transition>
  </main>
</template>
