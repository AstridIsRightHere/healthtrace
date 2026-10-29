<script setup lang="ts">
// 首页“最近就诊”区块（FR-013）：展示最近 3 条就诊记录，入口进入 /visits
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { errorMessage } from '../../api'
import { visitApi, type Visit } from '../../api/visit'
import { formatVisitDate, visitTitle } from '../../utils/visit'

const props = defineProps<{ profileId: number }>()

const visits = ref<Visit[]>([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    // 首页只取最近 3 条（FR-012），由后端 SQL LIMIT 限制
    visits.value = (await visitApi.list(props.profileId, 3)).items
  } catch (e) {
    error.value = errorMessage(e, '就诊记录加载失败，请稍后刷新')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="home-card recent-visits" aria-labelledby="recent-visits-title" data-testid="recent-visits">
    <div class="recent-visits-head">
      <h2 id="recent-visits-title">最近就诊</h2>
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'visits' }" data-testid="recent-visits-all">
        {{ visits.length ? '查看全部' : '添加就诊' }}
      </RouterLink>
    </div>
    <p v-if="loading" class="field-hint" role="status">加载中…</p>
    <p v-else-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <p v-else-if="visits.length === 0" class="field-hint">还没有就诊记录，看病后可以记一笔，方便日后回顾。</p>
    <ul v-else class="recent-visits-list">
      <li v-for="v in visits" :key="v.id" class="recent-visit-item">
        <span class="recent-visit-date">{{ formatVisitDate(v.visitDate) }}</span>
        <span class="recent-visit-title">{{ visitTitle(v) }}</span>
        <span v-if="v.reports.length" class="field-hint">关联 {{ v.reports.length }} 份报告</span>
      </li>
    </ul>
  </section>
</template>
