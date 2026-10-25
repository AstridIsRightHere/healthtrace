<script setup lang="ts">
// 指标趋势（FR-010/011）：指标下拉 + 时间区间 + 可比组折线图 + 结构化表格（图表的无障碍替代）
// 只做客观描述，不出现“好转/恶化”等判断（TC-010）。
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { trendApi, type MetricSummary, type TrendRangeKey, type TrendView } from '../api/trend'
import { useProfileStore } from '../stores/profile'
import { buildTrendOption, changeText, flagText, referenceText } from '../utils/trendOption'

// 图表组件按需加载，ECharts 不进入首屏包
const BaseChart = defineAsyncComponent(() => import('../components/charts/BaseChart.vue'))

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

const metricCode = ref<string>(typeof route.query.metric === 'string' ? route.query.metric : '')
const rangeKey = ref<TrendRangeKey>(
  RANGES.some((r) => r.key === route.query.range) ? (route.query.range as TrendRangeKey) : '30d',
)
const customFrom = ref(typeof route.query.from === 'string' ? route.query.from : '')
const customTo = ref(typeof route.query.to === 'string' ? route.query.to : '')

const trend = ref<TrendView | null>(null)
const trendLoading = ref(false)
const trendError = ref('')
const groupIndex = ref(0)
let requestSeq = 0

const profileId = computed(() => profile.profile?.id ?? null)
const currentGroup = computed(() => trend.value?.groups[groupIndex.value] ?? null)
const chartOption = computed(() =>
  trend.value && currentGroup.value && currentGroup.value.points.length > 0
    ? buildTrendOption(currentGroup.value, trend.value.range, trend.value.metric.standardName)
    : null,
)

/** 表格行：精确数值与非精确结果合并，按日期倒序 */
const tableRows = computed(() => {
  const g = currentGroup.value
  if (!g) return []
  const rows = [
    ...g.points.map((p) => ({
      key: `p${p.observationId}`,
      date: p.examinationDate,
      value: p.originalValue,
      reference: referenceText(p),
      flag: flagText(p.abnormalFlag),
      change: changeText(p.change),
      reportId: p.reportId,
      nonNumeric: false,
      id: p.observationId,
    })),
    ...g.nonNumeric.map((p) => ({
      key: `n${p.observationId}`,
      date: p.examinationDate,
      value: p.originalValue,
      reference: p.referenceText || '未提供',
      flag: flagText(p.abnormalFlag),
      change: '—',
      reportId: p.reportId,
      nonNumeric: true,
      id: p.observationId,
    })),
  ]
  return rows.sort((a, b) => (a.date === b.date ? b.id - a.id : a.date < b.date ? 1 : -1))
})

function groupLabel(index: number): string {
  const g = trend.value?.groups[index]
  if (!g) return ''
  const parts = [g.unitText || '未标注单位']
  if (g.specimenText) parts.push(g.specimenText)
  if (g.methodText) parts.push(g.methodText)
  return parts.join(' · ')
}

function syncQuery() {
  const query: Record<string, string> = {}
  if (metricCode.value) query.metric = metricCode.value
  query.range = rangeKey.value
  if (rangeKey.value === 'custom') {
    if (customFrom.value) query.from = customFrom.value
    if (customTo.value) query.to = customTo.value
  }
  router.replace({ name: 'trends', query })
}

async function loadTrend() {
  const pid = profileId.value
  if (!pid || !metricCode.value) {
    trend.value = null
    return
  }
  if (rangeKey.value === 'custom' && (!customFrom.value || !customTo.value)) {
    trend.value = null
    trendError.value = ''
    return
  }
  const seq = ++requestSeq
  trendLoading.value = true
  trendError.value = ''
  try {
    const result = await trendApi.trend(pid, {
      metricCode: metricCode.value,
      range: rangeKey.value,
      from: customFrom.value,
      to: customTo.value,
    })
    if (seq !== requestSeq) return
    trend.value = result
    groupIndex.value = 0
  } catch (error) {
    if (seq !== requestSeq) return
    trend.value = null
    trendError.value = errorMessage(error, '趋势加载失败，请稍后重试')
  } finally {
    if (seq === requestSeq) trendLoading.value = false
  }
}

function selectRange(key: TrendRangeKey) {
  rangeKey.value = key
}

watch([metricCode, rangeKey, customFrom, customTo], () => {
  syncQuery()
  void loadTrend()
})

onMounted(async () => {
  const pid = profileId.value
  if (!pid) return
  try {
    metrics.value = await trendApi.metrics(pid)
    if (!metrics.value.some((m) => m.code === metricCode.value)) {
      metricCode.value = metrics.value[0]?.code ?? ''
    }
    // metricCode 未变化时 watch 不会触发，这里主动加载一次
    void loadTrend()
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
    </header>

    <section class="home-card">
      <p v-if="metricsLoading" class="field-hint" role="status">加载中…</p>
      <p v-else-if="metricsError" class="notice notice-error" role="alert">{{ metricsError }}</p>
      <p v-else-if="metrics.length === 0" class="field-hint">
        还没有已确认的检查结果。上传报告并核对确认后，这里会显示指标趋势。
      </p>

      <template v-else>
        <div class="trend-controls">
          <label class="field trend-metric">
            <span>指标</span>
            <select v-model="metricCode">
              <option v-for="m in metrics" :key="m.code" :value="m.code">
                {{ m.standardName }}（{{ m.count }} 次）
              </option>
            </select>
          </label>

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

        <template v-else-if="trend">
          <p class="field-hint">
            {{ trend.range.from }} 至 {{ trend.range.to }} · 标准单位 {{ trend.metric.standardUnit }}
          </p>

          <div v-if="trend.unitWarning" class="notice" role="status">
            该指标的结果使用了不同的单位、标本或方法，已分开显示，不做换算，也不互相连线比较。
            如有疑问，请查看原始报告核对。
          </div>

          <div v-if="trend.groups.length > 1" class="trend-groups" role="tablist" aria-label="可比结果分组">
            <button
              v-for="(g, i) in trend.groups"
              :key="g.groupKey"
              type="button"
              role="tab"
              class="range-btn"
              :class="{ active: groupIndex === i }"
              :aria-selected="groupIndex === i"
              @click="groupIndex = i"
            >
              {{ groupLabel(i) }}
            </button>
          </div>

          <p v-if="trend.groups.length === 0" class="field-hint">这个时间区间内没有该指标的结果。</p>

          <template v-else-if="currentGroup">
            <p v-if="!currentGroup.matchesStandardUnit" class="field-hint">
              本组单位为“{{ currentGroup.unitText || '未标注' }}”，与标准单位不同，数值按原报告显示。
            </p>

            <BaseChart
              v-if="chartOption && currentGroup.points.length > 1"
              :option="chartOption"
              :aria-label="`${trend.metric.standardName}趋势图，完整数据见下方表格`"
            />
            <p v-else-if="currentGroup.points.length === 1" class="notice" role="status">
              目前只有 1 次结果，暂不能形成趋势。
            </p>
            <p v-else class="field-hint">本组没有可画图的精确数值，结果见下方表格。</p>

            <p class="trend-legend field-hint">
              ▲ 偏高　▼ 偏低　● 范围内或未判断；浅绿色区域 / 虚线为参考范围。间隔明显较长处折线断开，不做补点。
            </p>

            <div class="trend-table-wrap">
              <table class="trend-table">
                <caption class="visually-hidden">{{ trend.metric.standardName }}结果明细</caption>
                <thead>
                  <tr>
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
                  <tr v-for="row in tableRows" :key="row.key">
                    <td>{{ row.date }}</td>
                    <td>
                      {{ row.value }}
                      <span v-if="row.nonNumeric" class="trend-nonnumeric">非精确数值，未画入图中</span>
                    </td>
                    <td>{{ currentGroup.unitText || '—' }}</td>
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
            <p v-if="trend.metric.comparabilityNote" class="field-hint">说明：{{ trend.metric.comparabilityNote }}</p>
          </template>
        </template>
      </template>
    </section>
  </main>
</template>
