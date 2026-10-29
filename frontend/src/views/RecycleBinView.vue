<script setup lang="ts">
// 回收站（FR-016）：列表移除的报告在此可恢复或彻底删除；彻底删除后原件和所有分析数据被永久移除
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { errorMessage } from '../api'
import { reportApi, type ReportListItem } from '../api/report'
import DeleteReportDialog from '../components/home/DeleteReportDialog.vue'
import { useProfileStore } from '../stores/profile'

const profile = useProfileStore()

const items = ref<ReportListItem[]>([])
const loading = ref(true)
const message = ref('')
const busyId = ref<number | null>(null)
const purgeTarget = ref<ReportListItem | null>(null)
const purgeError = ref('')

/** 后端时间为 UTC（无时区后缀），按 UTC 解析再本地显示 */
function formatTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const date = new Date(/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : `${iso}Z`)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

async function load() {
  const pid = profile.profile?.id
  if (!pid) return
  loading.value = true
  message.value = ''
  try {
    items.value = (await reportApi.listRemoved(pid)).items
  } catch (error) {
    message.value = errorMessage(error, '回收站加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function restore(item: ReportListItem) {
  if (busyId.value !== null) return
  busyId.value = item.id
  message.value = ''
  try {
    await reportApi.restore(item.id)
    items.value = items.value.filter((r) => r.id !== item.id)
  } catch (error) {
    message.value = errorMessage(error, '恢复失败，请稍后重试')
  } finally {
    busyId.value = null
  }
}

function openPurge(item: ReportListItem) {
  purgeError.value = ''
  purgeTarget.value = item
}

function cancelPurge() {
  if (busyId.value === null) purgeTarget.value = null
}

async function confirmPurge() {
  const target = purgeTarget.value
  if (!target || busyId.value !== null) return
  busyId.value = target.id
  purgeError.value = ''
  try {
    await reportApi.remove(target.id)
    items.value = items.value.filter((r) => r.id !== target.id)
    purgeTarget.value = null
  } catch (error) {
    purgeError.value = errorMessage(error, '删除失败，请稍后重试')
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<template>
  <main class="home-page recycle-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'home' }">← 返回首页</RouterLink>
      <p class="brand">回收站</p>
    </header>

    <section class="home-card" aria-labelledby="recycle-title" data-testid="recycle-bin">
      <h2 id="recycle-title">已移除的报告</h2>
      <p class="field-hint">移除的报告不会出现在列表、趋势图和报告对比中。可以恢复，或彻底删除原件和所有分析数据。</p>
      <p v-if="message" class="notice notice-error" role="alert" data-testid="recycle-error">{{ message }}</p>
      <p v-if="loading" class="field-hint">加载中…</p>
      <p v-else-if="items.length === 0 && !message" class="field-hint" data-testid="recycle-empty">回收站是空的</p>
      <ul v-else class="recycle-list">
        <li v-for="item in items" :key="item.id" class="recycle-item" data-testid="recycle-item">
          <div>
            <p class="recycle-title">{{ item.title || '未命名报告' }}</p>
            <p class="field-hint">
              <span v-if="item.examinationDate">检查日期 {{ item.examinationDate }}</span>
              <span v-if="item.removedAt"> · 移除于 {{ formatTime(item.removedAt) }}</span>
            </p>
          </div>
          <div class="recycle-actions">
            <button
              type="button"
              class="btn-secondary btn-small"
              :disabled="busyId !== null"
              data-testid="recycle-restore"
              @click="restore(item)"
            >
              {{ busyId === item.id && !purgeTarget ? '恢复中…' : '恢复' }}
            </button>
            <button
              type="button"
              class="btn-danger-outline btn-small"
              :disabled="busyId !== null"
              data-testid="recycle-purge"
              @click="openPurge(item)"
            >
              彻底删除
            </button>
          </div>
        </li>
      </ul>
    </section>

    <DeleteReportDialog
      v-if="purgeTarget"
      :report-title="purgeTarget.title || '这份报告'"
      :deleting="busyId !== null"
      :error="purgeError"
      @confirm="confirmPurge"
      @cancel="cancelPurge"
    />
  </main>
</template>

<style scoped>
.recycle-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}
.recycle-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.recycle-title {
  margin: 0;
  font-weight: 600;
}
.recycle-actions {
  display: flex;
  gap: 8px;
}
</style>
