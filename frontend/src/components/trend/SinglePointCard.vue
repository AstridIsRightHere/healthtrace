<script setup lang="ts">
// 单点大卡片：某指标在可比组内只有 1 个精确数值时，不画空荡坐标系，直接突出最新数值。
// 状态用文字 + 符号表达，不只靠颜色（NFR-006）；只做客观描述（TC-010）。
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { TrendPoint } from '../../api/trend'
import { flagText, referenceText } from '../../utils/trendOption'

const props = defineProps<{
  name: string
  point: TrendPoint
  unitText: string | null
  condition?: string
  raw?: boolean
}>()

const flagClass = computed(() => {
  const f = props.point.abnormalFlag
  if (f === 'HIGH' || f === 'LOW') return 'flag-abnormal'
  if (f === 'NORMAL') return 'flag-normal'
  return 'flag-unknown'
})
</script>

<template>
  <article class="single-card" :aria-label="`${name}：${point.originalValue} ${unitText ?? ''}`">
    <header class="single-card-head">
      <h3 class="single-card-name">{{ name }}</h3>
      <span v-if="raw" class="chip-badge">未标准化</span>
    </header>
    <p class="single-card-value">
      <span class="single-card-number">{{ point.originalValue }}</span>
      <span class="single-card-unit">{{ unitText || '单位未标注' }}</span>
    </p>
    <p class="single-card-flag" :class="flagClass">{{ flagText(point.abnormalFlag) }}</p>
    <dl class="single-card-meta">
      <div>
        <dt>参考范围</dt>
        <dd>{{ referenceText(point) }}</dd>
      </div>
      <div>
        <dt>检查日期</dt>
        <dd>{{ point.examinationDate }}</dd>
      </div>
      <div v-if="condition">
        <dt>可比条件</dt>
        <dd>{{ condition }}</dd>
      </div>
    </dl>
    <p class="single-card-hint">仅有一次记录，暂不能形成趋势。</p>
    <RouterLink class="text-link" :to="{ name: 'report-review', params: { reportId: String(point.reportId) } }">
      查看原始报告
    </RouterLink>
  </article>
</template>
