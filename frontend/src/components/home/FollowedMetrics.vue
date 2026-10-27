<script setup lang="ts">
// 首页“关注指标”横向卡片（FR-012）。关注项暂由 Pinia 模拟（FR-009 未实现）。
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { trendApi } from '../../api/trend'
import { useFollowStore } from '../../stores/follow'
import { DIRECTION_ARROW, DIRECTION_TEXT, toFollowCard, type FollowCard } from '../../utils/followMetric'

const props = defineProps<{ profileId: number }>()
const follow = useFollowStore()

const cards = ref<FollowCard[]>([])
const loading = ref(true)

onMounted(async () => {
  // 每项独立请求，单项失败只显示“暂无记录”，不影响其他卡片
  const views = await Promise.all(
    follow.codes.map((code) =>
      trendApi.trend(props.profileId, { metricCode: code, range: '5y' }).catch(() => null),
    ),
  )
  cards.value = follow.codes.map((code, i) => toFollowCard(code, views[i] ?? null))
  loading.value = false
})

function cardLabel(c: FollowCard): string {
  if (!c.value) return `${c.name}：暂无记录，查看趋势`
  return `${c.name}：最新 ${c.value} ${c.unit ?? ''}，${DIRECTION_TEXT[c.direction]}，查看趋势`
}
</script>

<template>
  <section class="home-card follow-metrics" aria-labelledby="follow-title" data-testid="follow-metrics">
    <div class="recent-visits-head">
      <h2 id="follow-title">关注指标</h2>
      <span class="field-hint">左右滑动查看</span>
    </div>
    <p v-if="loading" class="field-hint" role="status">加载中…</p>
    <ul v-else class="follow-list">
      <li v-for="c in cards" :key="c.code">
        <RouterLink
          class="follow-card"
          :class="{ 'follow-card-abnormal': c.abnormalFlag === 'HIGH' || c.abnormalFlag === 'LOW' }"
          :to="{ name: 'trends', query: { metric: c.code } }"
          :aria-label="cardLabel(c)"
          data-testid="follow-card"
          :data-code="c.code"
        >
          <span class="follow-name">{{ c.name }}</span>
          <span v-if="c.value" class="follow-value">
            {{ c.value }}
            <span
              v-if="c.direction !== 'none'"
              class="follow-arrow"
              :class="`follow-arrow-${c.direction}`"
              :title="DIRECTION_TEXT[c.direction]"
              aria-hidden="true"
            >{{ DIRECTION_ARROW[c.direction] }}</span>
          </span>
          <span v-else class="follow-value follow-empty">暂无记录</span>
          <span class="follow-unit">{{ c.unit || '\u00a0' }}</span>
          <span class="follow-date">{{ c.date ?? '上传报告后显示' }}</span>
        </RouterLink>
      </li>
    </ul>
  </section>
</template>
