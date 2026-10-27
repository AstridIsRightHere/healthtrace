<script setup lang="ts">
// 报告对比（FR-010/011）：选择两份已确认报告，按“上次 / 此次”对照共有指标。
// 只做客观描述，不出现“好转/恶化”等判断（TC-010）；单位、标本或方法不同的结果不计算变化（DEC-015）。
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { compareApi, type ComparisonView } from '../api/compare'
import { reportApi, type ReportGroup, type ReportListItem } from '../api/report'
import ReportSelect from '../components/report/ReportSelect.vue'
import { useProfileStore } from '../stores/profile'
import {
  cellText,
  defaultPair,
  filterRows,
  groupChipLabel,
  initialGroupKey,
  rowReference,
  rowStatusText,
} from '../utils/reportCompare'
import { changeText } from '../utils/trendOption'

const profile = useProfileStore()
const route = useRoute()
const router = useRouter()

const groups = ref<ReportGroup[]>([])
const groupKey = ref<string | null>(null)
const reportsLoading = ref(true)
const reportsError = ref('')

const currentGroup = computed(() => groups.value.find((g) => g.key === groupKey.value) ?? null)
const reports = computed<ReportListItem[]>(() => currentGroup.value?.reports ?? [])
const totalReports = computed(() => groups.value.reduce((n, g) => n + g.count, 0))

const baseId = ref<number | null>(null)
const targetId = ref<number | null>(null)
const onlyBoth = ref(true)

const result = ref<ComparisonView | null>(null)
const compareLoading = ref(false)
const compareError = ref('')
let requestSeq = 0

const rows = computed(() => (result.value ? filterRows(result.value.rows, onlyBoth.value) : []))
const baseDate = computed(() => result.value?.base.examinationDate ?? '日期未填写')
const targetDate = computed(() => result.value?.target.examinationDate ?? '日期未填写')

function swap() {
  const b = baseId.value
  baseId.value = targetId.value
  targetId.value = b
}

async function loadComparison() {
  const b = baseId.value
  const t = targetId.value
  if (b == null || t == null || b === t) {
    result.value = null
    return
  }
  const seq = ++requestSeq
  compareLoading.value = true
  compareError.value = ''
  try {
    const data = await compareApi.compare(b, t)
    if (seq !== requestSeq) return
    result.value = data
  } catch (error) {
    if (seq !== requestSeq) return
    result.value = null
    compareError.value = errorMessage(error, '对比加载失败，请稍后重试')
  } finally {
    if (seq === requestSeq) compareLoading.value = false
  }
}

watch([baseId, targetId], () => void loadComparison())

/** 切换报告类型：自动选中该类型最新的两份（此次=最新，上次=第二新）；不足两份清空选择 */
function selectGroup(key: string | null, syncUrl = true) {
  groupKey.value = key
  const pair = defaultPair(reports.value)
  baseId.value = pair?.baseId ?? null
  targetId.value = pair?.targetId ?? null
  if (syncUrl && key) void router.replace({ query: { ...route.query, type: key } })
}

onMounted(async () => {
  const pid = profile.profile?.id
  if (!pid) {
    reportsLoading.value = false
    return
  }
  try {
    groups.value = await reportApi.listConfirmedGroups(pid)
    const preferred = typeof route.query.type === 'string' ? route.query.type : null
    selectGroup(initialGroupKey(groups.value, preferred), false)
  } catch (error) {
    reportsError.value = errorMessage(error, '报告列表加载失败，请稍后刷新')
  } finally {
    reportsLoading.value = false
  }
})
</script>

<template>
  <main class="trend-page compare-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'home' }">← 返回首页</RouterLink>
      <p class="brand">报告对比</p>
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'trends' }">指标趋势</RouterLink>
    </header>

    <section class="home-card">
      <p v-if="reportsLoading" class="field-hint" role="status">加载中…</p>
      <p v-else-if="reportsError" class="notice notice-error" role="alert">{{ reportsError }}</p>
      <p v-else-if="totalReports === 0" class="field-hint">
        还没有已确认的报告。上传报告并核对确认后再来看看。
      </p>

      <template v-else>
        <div class="compare-types">
          <span class="metric-picker-title" id="compare-type-title">报告类型</span>
          <div class="chip-group" role="radiogroup" aria-labelledby="compare-type-title">
            <button
              v-for="g in groups"
              :key="g.key"
              type="button"
              role="radio"
              class="chip"
              :class="{ 'chip-active': g.key === groupKey }"
              :aria-checked="g.key === groupKey"
              :title="g.label"
              @click="selectGroup(g.key)"
            >
              <span class="chip-name">{{ groupChipLabel(g) }}</span>
            </button>
          </div>
        </div>

        <p v-if="reports.length < 2" class="field-hint compare-empty" role="status">
          该类型报告只有一份，无法进行对比。
        </p>
      </template>

      <template v-if="!reportsLoading && !reportsError && reports.length >= 2">
        <p class="field-hint">已自动选中该类型最新的两份报告，可在下方更换。</p>
        <div class="compare-controls">
          <ReportSelect v-model="baseId" label="上次" :reports="reports" :disabled-id="targetId" />
          <button type="button" class="btn-ghost btn-small compare-swap" aria-label="交换上次与此次" @click="swap">
            ⇅ 交换
          </button>
          <ReportSelect v-model="targetId" label="此次" :reports="reports" :disabled-id="baseId" />
        </div>

        <label class="compare-toggle">
          <input v-model="onlyBoth" type="checkbox" />
          <span>只看两份都有的指标</span>
        </label>

        <p v-if="compareLoading" class="field-hint" role="status">加载中…</p>
        <p v-else-if="compareError" class="notice notice-error" role="alert">{{ compareError }}</p>

        <template v-else-if="result">
          <p v-if="rows.length === 0" class="field-hint">
            两份报告没有共同的检查项目。可以关闭“只看两份都有的指标”查看全部结果。
          </p>

          <div v-else class="trend-table-wrap">
            <table class="trend-table compare-table">
              <caption class="visually-hidden">报告对比：上次 {{ baseDate }}，此次 {{ targetDate }}</caption>
              <thead>
                <tr>
                  <th scope="col">指标</th>
                  <th scope="col">上次（{{ baseDate }}）</th>
                  <th scope="col">此次（{{ targetDate }}）</th>
                  <th scope="col">变化</th>
                  <th scope="col">参考范围</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.key">
                  <th scope="row" data-label="指标">
                    {{ row.name }}
                    <span v-if="!row.standard" class="chip-badge">未标准化</span>
                  </th>
                  <td :data-label="`上次（${baseDate}）`">{{ cellText(row.base) }}</td>
                  <td :data-label="`此次（${targetDate}）`">{{ cellText(row.target) }}</td>
                  <td data-label="变化">
                    <template v-if="row.status === 'COMPARABLE'">{{ changeText(row.change) }}</template>
                    <span v-else class="compare-note">{{ rowStatusText(row.status) }}</span>
                  </td>
                  <td data-label="参考范围">{{ rowReference(row) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <p class="field-hint">
            “变化”只在两次结果单位、标本、方法一致且都是精确数值时计算，不做单位换算，也不代表病情判断。
            如有疑问，请查看原始报告：
            <RouterLink class="text-link" :to="{ name: 'report-review', params: { reportId: String(result.base.id) } }">
              上次报告
            </RouterLink>
            ·
            <RouterLink class="text-link" :to="{ name: 'report-review', params: { reportId: String(result.target.id) } }">
              此次报告
            </RouterLink>
          </p>
        </template>
      </template>
    </section>
  </main>
</template>
