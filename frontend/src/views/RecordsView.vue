<script setup lang="ts">
// 档案（一级 Tab，PG-010 报告列表）：已确认报告按种类（检验 / 影像 / 其他）筛选，
// 影像与其他报告展示结论摘要；筛选值同步到 query.kind，便于从上传页归档后直接定位。
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { reportApi, type ReportListItem } from '../api/report'
import { useProfileStore } from '../stores/profile'
import { KIND_FILTERS, conclusionSummary, parseKindFilter, reportKindLabel, type KindFilter } from '../utils/reportKind'

const route = useRoute()
const router = useRouter()
const profile = useProfileStore()

const filter = ref<KindFilter>(parseKindFilter(route.query.kind))
const reports = ref<ReportListItem[]>([])
const loading = ref(false)
const error = ref('')
let seq = 0

const uploadTarget = computed(() =>
  filter.value === 'ALL' || filter.value === 'STRUCTURED'
    ? { name: 'report-upload' }
    : { name: 'report-upload', query: { kind: filter.value } },
)

async function load() {
  const profileId = profile.profile?.id
  if (!profileId) return
  const current = ++seq
  loading.value = true
  error.value = ''
  try {
    const items = await reportApi.listConfirmed(profileId, filter.value === 'ALL' ? undefined : filter.value)
    if (current === seq) reports.value = items
  } catch (e) {
    if (current === seq) error.value = errorMessage(e, '报告列表加载失败，请稍后重试')
  } finally {
    if (current === seq) loading.value = false
  }
}

function select(value: KindFilter) {
  if (value === filter.value) return
  filter.value = value
  void router.replace({ name: 'records', query: value === 'ALL' ? {} : { kind: value } })
}

watch(
  () => route.query.kind,
  (raw) => {
    filter.value = parseKindFilter(raw)
  },
)
watch(filter, () => void load())
onMounted(() => void load())
</script>

<template>
  <main class="home-page tab-page" data-testid="records-page">
    <header class="tab-header">
      <h1>档案</h1>
      <p class="field-hint">集中管理检验报告、影像报告与其他就医资料。</p>
    </header>

    <section class="home-card records-upload" aria-labelledby="records-upload-title" data-testid="records-upload">
      <div>
        <h2 id="records-upload-title">上传报告</h2>
        <p class="field-hint">检验报告可识别指标；超声、CT 等影像报告保存原件与结论。</p>
      </div>
      <RouterLink class="btn-secondary btn-small" :to="uploadTarget" data-testid="records-upload-link">
        <span aria-hidden="true">＋</span>&nbsp;上传报告
      </RouterLink>
    </section>

    <section class="home-card" aria-labelledby="records-list-title" data-testid="records-list">
      <div class="recent-visits-head">
        <h2 id="records-list-title">报告列表</h2>
        <RouterLink class="btn-ghost btn-small" :to="{ name: 'report-compare' }">报告对比</RouterLink>
      </div>

      <div class="kind-filter" role="group" aria-label="按报告种类筛选" data-testid="records-filter">
        <button
          v-for="option in KIND_FILTERS"
          :key="option.value"
          type="button"
          class="kind-chip"
          :class="{ active: filter === option.value }"
          :aria-pressed="filter === option.value"
          :data-kind="option.value"
          data-testid="records-filter-chip"
          @click="select(option.value)"
        >
          {{ option.label }}
        </button>
      </div>

      <p v-if="error" class="notice notice-error" role="alert">
        {{ error }}
        <button class="btn-ghost btn-small" type="button" @click="load">重试</button>
      </p>
      <p v-else-if="loading && reports.length === 0" class="field-hint" role="status">加载中…</p>
      <div v-else-if="reports.length === 0" class="placeholder-block" role="note" data-testid="records-empty">
        <p class="placeholder-title">还没有{{ filter === 'ALL' ? '' : reportKindLabel(filter) }}</p>
        <p class="field-hint">上传后会按检查日期归档在这里。</p>
      </div>
      <ul v-else class="records-items" data-testid="records-items">
        <li v-for="item in reports" :key="item.id" class="records-item" data-testid="records-item" :data-kind="item.reportKind">
          <RouterLink class="records-item-link" :to="{ name: 'report-review', params: { reportId: String(item.id) } }">
            <span class="kind-badge" :data-kind="item.reportKind">{{ reportKindLabel(item.reportKind) }}</span>
            <span class="records-item-main">
              <span class="records-item-title">{{ item.title?.trim() || '未命名报告' }}</span>
              <span class="field-hint">{{ item.examinationDate ?? '检查日期未填写' }} · {{ item.photoCount }} 个文件</span>
              <span v-if="item.conclusionText" class="records-item-conclusion" data-testid="records-item-conclusion">
                {{ conclusionSummary(item.conclusionText) }}
              </span>
            </span>
          </RouterLink>
        </li>
      </ul>
    </section>
  </main>
</template>
