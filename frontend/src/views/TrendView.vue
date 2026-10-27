<script setup lang="ts">
// 指标趋势（FR-010/011）：指标胶囊多选 + 时间区间 + 按可比条件分图的折线图 + 结构化表格（图表的无障碍替代）
// DEC-015：只有单位、标本、方法完全一致的结果才画在同一张图里，不做换算；只做客观描述（TC-010）。
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { trendApi, type MetricSummary, type TrendRangeKey, type TrendView } from '../api/trend'
import MetricPicker from '../components/trend/MetricPicker.vue'
import SinglePointCard from '../components/trend/SinglePointCard.vue'
import { useProfileStore } from '../stores/profile'
import { composeTrends, conditionLabel, parseMetricCodes, type ChartPanel, type SeriesSlice } from '../utils/trendCompose'
import { buildMultiSeriesOption, changeText, flagText, referenceText } from '../utils/trendOption'
import { pickDefaultRange } from '../utils/trendRange'

// 图表组件按需加载，ECharts 不进入首屏包
const BaseChart = defineAsyncComponent(() => import('../components/charts/BaseChart.vue'))

const MAX_METRICS = 6

const RANGES: Array<{ key: TrendRangeKey; label: string }> = [
  { key: '7d', label: '7天' },
  { key: '30d', label: '30天' },
  { key: '1y', label: '1年' },
  { key: '5y', label: '5年' },
  { key: 'custom', label: '自定义' },
]

const route = useRoute()
const router = useRouter()
const profile = useProfileStore()

const metrics = ref<MetricSummary[]>([])
const metricsLoading = ref(true)
const metricsError = ref('')

// 兼容旧链接 ?metric=GLU，也支持 ?metric=GLU,TC,TG
const selectedCodes = ref<string[]>(parseMetricCodes(route.query.metric, MAX_METRICS))
// URL 显式指定区间时尊重用户选择；否则在指标加载后按最近结果日期自动选默认区间
const rangeFromUrl = RANGES.some((r) => r.key === route.query.range)
const rangeKey = ref<TrendRangeKey>(rangeFromUrl ? (route.query.range as TrendRangeKey) : '30d')
const customFrom = ref(typeof route.query.from === 'string' ? route.query.from : '')
const customTo = ref(typeof route.query.to === 'string' ? route.query.to : '')

const trends = ref<TrendView[]>([])
const trendLoading = ref(false)
const trendError = ref('')
let requestSeq = 0

const profileId = computed(() => profile.profile?.id ?? null)
const composed = computed(() => composeTrends(trends.value))
const range = computed(() => trends.value[0]?.range ?? null)
const multiSelected = computed(() => selectedCodes.value.length > 1)
const anyUnitWarning = computed(() => trends.value.some((t) => t.unitWarning))
const notes = computed(() =>
  trends.value
    .filter((t) => t.metric.comparabilityNote)
    .map((t) => ({ code: t.metric.code, name: t.metric.standardName, note: t.metric.comparabilityNote })),
)
const hasAnyData = computed(() => trends.value.some((t) => t.groups.length > 0))

function panelOption(panel: ChartPanel) {
  if (!range.value) return null
  return buildMultiSeriesOption(
    panel.series.map((s) => ({ name: s.metric.standardName, group: s.group })),
    range.value,
    panel.unitText,
  )
}

function panelTitle(panel: ChartPanel): string {
  return panel.series.map((s) => s.metric.standardName).join('、')
}

interface TableRow {
  key: string
  metric: string
  date: string
  value: string
  reference: string
  flag: string
  change: string
  reportId: number
  nonNumeric: boolean
  id: number
}

/** 表格行：精确数值与非精确结果合并，按日期倒序 */
function rowsOf(slices: SeriesSlice[]): TableRow[] {
  const rows: TableRow[] = []
  for (const s of slices) {
    for (const p of s.points) {
      rows.push({
        key: `p${p.observationId}`,
        metric: s.metric.standardName,
        date: p.examinationDate,
        value: p.originalValue,
        reference: referenceText(p),
        flag: flagText(p.abnormalFlag),
        change: changeText(p.change),
        reportId: p.reportId,
        nonNumeric: false,
        id: p.observationId,
      })
    }
    for (const p of s.nonNumeric) {
      rows.push({
        key: `n${p.observationId}`,
        metric: s.metric.standardName,
        date: p.examinationDate,
        value: p.originalValue,
        reference: p.referenceText || '未提供',
        flag: flagText(p.abnormalFlag),
        change: '—',
        reportId: p.reportId,
        nonNumeric: true,
        id: p.observationId,
      })
    }
  }
  return rows.sort((a, b) => (a.date === b.date ? b.id - a.id : a.date < b.date ? 1 : -1))
}

function syncQuery() {
  const query: Record<string, string> = {}
  if (selectedCodes.value.length) query.metric = selectedCodes.value.join(',')
  query.range = rangeKey.value
  if (rangeKey.value === 'custom') {
    if (customFrom.value) query.from = customFrom.value
    if (customTo.value) query.to = customTo.value
  }
  router.replace({ name: 'trends', query })
}

async function loadTrends() {
  const pid = profileId.value
  if (!pid || selectedCodes.value.length === 0) {
    trends.value = []
    return
  }
  if (rangeKey.value === 'custom' && (!customFrom.value || !customTo.value)) {
    trends.value = []
    trendError.value = ''
    return
  }
  const seq = ++requestSeq
  trendLoading.value = true
  trendError.value = ''
  try {
    // 每个指标复用单指标趋势接口，并行请求；顺序与用户选择一致
    const results = await Promise.all(
      selectedCodes.value.map((code) =>
        trendApi.trend(pid, { metricCode: code, range: rangeKey.value, from: customFrom.value, to: customTo.value }),
      ),
    )
    if (seq !== requestSeq) return
    trends.value = results
  } catch (error) {
    if (seq !== requestSeq) return
    trends.value = []
    trendError.value = errorMessage(error, '趋势加载失败，请稍后重试')
  } finally {
    if (seq === requestSeq) trendLoading.value = false
  }
}

function selectRange(key: TrendRangeKey) {
  rangeKey.value = key
}

watch([selectedCodes, rangeKey, customFrom, customTo], () => {
  syncQuery()
  void loadTrends()
})

onMounted(async () => {
  const pid = profileId.value
  if (!pid) {
    metricsLoading.value = false
    return
  }
  try {
    metrics.value = await trendApi.metrics(pid)
    const known = selectedCodes.value.filter((c) => metrics.value.some((m) => m.code === c))
    if (known.length === 0 && metrics.value[0]) known.push(metrics.value[0].code)
    if (!rangeFromUrl) {
      // 取所选指标中最近的结果日期，选能覆盖它的最短区间
      const latest = metrics.value
        .filter((m) => known.includes(m.code))
        .map((m) => m.latestDate)
        .filter((d): d is string => !!d)
        .sort()
        .pop()
      rangeKey.value = pickDefaultRange(latest)
    }
    const changed = known.join(',') !== selectedCodes.value.join(',')
    selectedCodes.value = known
    // 选择未变化时 watch 不会触发，这里主动加载一次
    if (!changed) void loadTrends()
  } catch (error) {
    metricsError.value = errorMessage(error, '指标列表加载失败，请稍后刷新')
  } finally {
    metricsLoading.value = false
  }
})
</script>

<template>
  <main class="trend-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'home' }">← 返回首页</RouterLink>
      <p class="brand">指标趋势</p>
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'report-compare' }">报告对比</RouterLink>
    </header>

    <section class="home-card">
      <p v-if="metricsLoading" class="field-hint" role="status">加载中…</p>
      <p v-else-if="metricsError" class="notice notice-error" role="alert">{{ metricsError }}</p>
      <p v-else-if="metrics.length === 0" class="field-hint">
        还没有已确认的检查结果。上传报告并核对确认后，这里会显示指标趋势。
      </p>

      <template v-else>
        <div class="trend-controls">
          <MetricPicker v-model="selectedCodes" :metrics="metrics" :max="MAX_METRICS" />

          <div class="trend-ranges" role="group" aria-label="时间区间">
            <button
              v-for="r in RANGES"
              :key="r.key"
              type="button"
              class="range-btn"
              :class="{ active: rangeKey === r.key }"
              :aria-pressed="rangeKey === r.key"
              @click="selectRange(r.key)"
            >
              {{ r.label }}
            </button>
          </div>

          <div v-if="rangeKey === 'custom'" class="trend-custom">
            <label class="field">
              <span>开始日期</span>
              <input v-model="customFrom" type="date" :max="customTo || undefined" />
            </label>
            <label class="field">
              <span>结束日期</span>
              <input v-model="customTo" type="date" :min="customFrom || undefined" />
            </label>
          </div>
        </div>

        <p v-if="rangeKey === 'custom' && (!customFrom || !customTo)" class="field-hint">
          请选择开始和结束日期（最长 20 年）。
        </p>
        <p v-else-if="trendLoading" class="field-hint" role="status">加载中…</p>
        <p v-else-if="trendError" class="notice notice-error" role="alert">{{ trendError }}</p>

        <template v-else-if="range">
          <p class="field-hint">{{ range.from }} 至 {{ range.to }}</p>

          <div v-if="composed.splitByComparability" class="notice" role="status">
            单位不同，已分图展示。单位、标本或方法不一致的结果不做换算，也不互相连线比较。
          </div>
          <div v-else-if="anyUnitWarning" class="notice" role="status">
            部分结果使用了不同的单位、标本或方法，已分开显示，不做换算。如有疑问，请查看原始报告核对。
          </div>

          <p v-if="!hasAnyData" class="field-hint">这个时间区间内没有所选指标的结果，可以换一个更长的时间区间。</p>

          <section v-for="panel in composed.panels" :key="panel.key" class="trend-panel">
            <h2 class="trend-panel-title">
              {{ panelTitle(panel) }}
              <span class="trend-panel-cond">{{ conditionLabel(panel) }}</span>
              <span v-if="!panel.standard" class="chip-badge">未标准化</span>
            </h2>
            <BaseChart
              v-if="panelOption(panel)"
              :option="panelOption(panel)!"
              :aria-label="`${panelTitle(panel)}趋势图，完整数据见下方表格`"
            />
            <div class="trend-table-wrap">
              <table class="trend-table">
                <caption class="visually-hidden">{{ panelTitle(panel) }}结果明细</caption>
                <thead>
                  <tr>
                    <th v-if="multiSelected" scope="col">指标</th>
                    <th scope="col">日期</th>
                    <th scope="col">结果</th>
                    <th scope="col">单位</th>
                    <th scope="col">参考范围</th>
                    <th scope="col">状态</th>
                    <th scope="col">与上次相比</th>
                    <th scope="col">来源</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in rowsOf(panel.series)" :key="row.key">
                    <td v-if="multiSelected">{{ row.metric }}</td>
                    <td>{{ row.date }}</td>
                    <td>
                      {{ row.value }}
                      <span v-if="row.nonNumeric" class="trend-nonnumeric">非精确数值，未画入图中</span>
                    </td>
                    <td>{{ panel.unitText || '—' }}</td>
                    <td>{{ row.reference }}</td>
                    <td>{{ row.flag }}</td>
                    <td>{{ row.change }}</td>
                    <td>
                      <RouterLink
                        class="text-link"
                        :to="{ name: 'report-review', params: { reportId: String(row.reportId) } }"
                      >
                        查看原始报告
                      </RouterLink>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <p v-if="composed.panels.length" class="trend-legend field-hint">
            <template v-if="composed.panels.some((p) => p.series.length === 1)">
              ▲ 偏高　▼ 偏低　● 范围内或未判断；浅绿色区域 / 虚线为参考范围。
            </template>
            多个指标同图时，各指标参考范围不同，请在提示框和表格中查看。间隔明显较长处折线断开，不做补点。
          </p>

          <div v-if="composed.singles.length" class="single-grid">
            <SinglePointCard
              v-for="s in composed.singles"
              :key="s.id"
              :name="s.metric.standardName"
              :point="s.points[0]!"
              :unit-text="s.group.unitText"
              :condition="conditionLabel(s.group)"
              :raw="!s.standard"
            />
          </div>

          <section v-for="s in composed.noNumeric" :key="s.id" class="trend-panel">
            <h2 class="trend-panel-title">
              {{ s.metric.standardName }}
              <span class="trend-panel-cond">{{ conditionLabel(s.group) }}</span>
            </h2>
            <p class="field-hint">本组没有可画图的精确数值，结果如下。</p>
            <ul class="trend-nonnumeric-list">
              <li v-for="p in s.nonNumeric" :key="p.observationId">
                {{ p.examinationDate }}：{{ p.originalValue }}
                <RouterLink class="text-link" :to="{ name: 'report-review', params: { reportId: String(p.reportId) } }">
                  查看原始报告
                </RouterLink>
              </li>
            </ul>
          </section>

          <p v-for="n in notes" :key="n.code" class="field-hint">说明（{{ n.name }}）：{{ n.note }}</p>
        </template>
      </template>
    </section>
  </main>
</template>
