<script setup lang="ts">
// 首页“关注指标”横向卡片（FR-009 / FR-012）。关注项来自后端 followed_metric，
// 请求失败一律显示“加载失败，请点击重试”，绝不把错误伪装成“暂无记录”。
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { trendApi } from '../../api/trend'
import { useFollowStore } from '../../stores/follow'
import { DIRECTION_ARROW, DIRECTION_TEXT, toFollowCardFromSummary, type FollowCard } from '../../utils/followMetric'
import ManageFollowDialog from './ManageFollowDialog.vue'

const props = defineProps<{ profileId: number }>()
const follow = useFollowStore()

type CardSlot =
  | { code: string; name: string; state: 'loading' }
  | { code: string; name: string; state: 'error' }
  | { code: string; name: string; state: 'ready'; card: FollowCard }

/** 关注列表本身的加载状态 */
const listState = ref<'loading' | 'ready' | 'error'>('loading')
const slots = ref<CardSlot[]>([])

const managing = ref(false)
const saving = ref(false)
const saveError = ref('')

/** 递增序号：较早发出的批量请求晚返回时丢弃，避免覆盖新结果 */
let requestSeq = 0

async function loadSummaries(): Promise<void> {
  const items = follow.items.map((item) => ({ code: item.metricCode, name: item.name }))
  slots.value = items.map(({ code, name }) => ({ code, name, state: 'loading' }))
  if (items.length === 0) return
  const seq = ++requestSeq
  try {
    // 一次批量请求取全部关注指标的最新值（替代逐项 5 年趋势请求）
    const view = await trendApi.summaries(
      props.profileId,
      items.map((i) => i.code),
    )
    if (seq !== requestSeq) return
    const byCode = new Map(view.items.map((s) => [s.metricCode, s]))
    slots.value = items.map(({ code, name }) => ({
      code,
      name,
      state: 'ready',
      card: toFollowCardFromSummary(code, name, byCode.get(code)),
    }))
  } catch {
    if (seq !== requestSeq) return
    slots.value = items.map(({ code, name }) => ({ code, name, state: 'error' }))
  }
}

function rebuildCards(): void {
  void loadSummaries()
}

async function loadList(force = false): Promise<void> {
  listState.value = 'loading'
  try {
    await follow.load(props.profileId, force)
    listState.value = 'ready'
    rebuildCards()
  } catch {
    listState.value = 'error'
  }
}

onMounted(() => loadList())

function openManage(): void {
  saveError.value = ''
  managing.value = true
}

async function saveManage(codes: string[]): Promise<void> {
  saving.value = true
  saveError.value = ''
  try {
    await follow.save(props.profileId, codes)
    managing.value = false
    rebuildCards()
  } catch {
    saveError.value = '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function cardLabel(c: FollowCard): string {
  if (!c.value) return `${c.name}：暂无记录，查看趋势`
  return `${c.name}：最新 ${c.value} ${c.unit ?? ''}，${DIRECTION_TEXT[c.direction]}，查看趋势`
}
</script>

<template>
  <section class="home-card follow-metrics" aria-labelledby="follow-title" data-testid="follow-metrics">
    <div class="recent-visits-head">
      <h2 id="follow-title">关注指标</h2>
      <button
        v-if="listState === 'ready'"
        class="btn-secondary"
        type="button"
        data-testid="follow-manage"
        @click="openManage"
      >管理关注</button>
    </div>

    <p v-if="listState === 'loading'" class="field-hint" role="status">加载中…</p>
    <div v-else-if="listState === 'error'" class="notice notice-error" role="alert" data-testid="follow-list-error">
      加载失败，
      <button class="btn-link" type="button" data-testid="follow-retry" @click="loadList(true)">请点击重试</button>
    </div>
    <template v-else>
      <p v-if="slots.length === 0" class="field-hint">
        尚未关注任何指标，
        <button class="btn-link" type="button" @click="openManage">去添加</button>
      </p>
      <ul v-else class="follow-list">
        <li v-for="s in slots" :key="s.code">
          <RouterLink
            v-if="s.state === 'ready'"
            class="follow-card"
            :class="{ 'follow-card-abnormal': s.card.abnormalFlag === 'HIGH' || s.card.abnormalFlag === 'LOW' }"
            :to="{ name: 'trends', query: { metric: s.code } }"
            :aria-label="cardLabel(s.card)"
            data-testid="follow-card"
            :data-code="s.code"
          >
            <span class="follow-name">{{ s.card.name }}</span>
            <span v-if="s.card.value" class="follow-value">
              {{ s.card.value }}
              <span
                v-if="s.card.direction !== 'none'"
                class="follow-arrow"
                :class="`follow-arrow-${s.card.direction}`"
                :title="DIRECTION_TEXT[s.card.direction]"
                aria-hidden="true"
              >{{ DIRECTION_ARROW[s.card.direction] }}</span>
            </span>
            <span v-else class="follow-value follow-empty">暂无记录</span>
            <span class="follow-unit">{{ s.card.unit || '\u00a0' }}</span>
            <span class="follow-date">{{ s.card.date ?? '上传报告后显示' }}</span>
          </RouterLink>
          <button
            v-else-if="s.state === 'error'"
            class="follow-card follow-card-error"
            type="button"
            data-testid="follow-card-error"
            :data-code="s.code"
            :aria-label="`${s.name}：加载失败，请点击重试`"
            @click="loadSummaries()"
          >
            <span class="follow-name">{{ s.name }}</span>
            <span class="follow-value follow-error" role="alert">加载失败</span>
            <span class="follow-date">请点击重试</span>
          </button>
          <div v-else class="follow-card" data-testid="follow-card-loading" :data-code="s.code">
            <span class="follow-name">{{ s.name }}</span>
            <span class="follow-value follow-empty" role="status">加载中…</span>
          </div>
        </li>
      </ul>
    </template>

    <ManageFollowDialog
      v-if="managing"
      :initial-codes="follow.codes"
      :saving="saving"
      :error="saveError"
      @save="saveManage"
      @cancel="managing = false"
    />
  </section>
</template>
