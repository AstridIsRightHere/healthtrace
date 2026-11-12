<script setup lang="ts">
// 未标准化指标（FR-009，docs/31）：已确认报告中没有对应标准指标的结果，
// 用户可主动把它设为本档案的“临时指标”并加入首页关注；同名同单位的结果会自动归在一起（DEC-015）。
import { computed, ref } from 'vue'
import { errorMessage, fieldErrors } from '../../api'
import type { ObservationView } from '../../api/ocr'
import { tempMetricApi, type TempMetric } from '../../api/tempMetric'
import { useFollowStore } from '../../stores/follow'

const props = defineProps<{ profileId: number; observations: ObservationView[] }>()
const emit = defineEmits<{ created: [metric: TempMetric] }>()

const follow = useFollowStore()
const busyId = ref<number | null>(null)
const message = ref('')
const notice = ref('')

/** 没有映射到标准指标的结果行；已归入临时指标的也展示，以便看到状态 */
const unmapped = computed(() => props.observations.filter((o) => !o.metricCode))

function unitLabel(o: ObservationView): string {
  return o.unitText ? `${o.originalValue} ${o.unitText}` : o.originalValue
}

function isFollowed(code: string | null | undefined): boolean {
  return !!code && follow.codes.includes(code)
}

async function makeTemp(o: ObservationView) {
  if (busyId.value !== null) return
  busyId.value = o.id
  message.value = ''
  notice.value = ''
  try {
    const metric = await tempMetricApi.create(props.profileId, o.id, true)
    await follow.load(props.profileId, true)
    notice.value = `已将“${metric.name}”设为临时指标并加入首页关注，共 ${metric.observationCount} 条结果。`
    emit('created', metric)
  } catch (error) {
    const fields = fieldErrors(error)
    message.value = fields.follow
      ? '关注的指标已达上限，请先在“我的 → 关注指标管理”中取消一些再试。'
      : errorMessage(error, '设置临时指标失败，请稍后重试')
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <section v-if="unmapped.length" class="home-card" aria-labelledby="temp-metric-title" data-testid="temp-metric-panel">
    <h2 id="temp-metric-title">未标准化的指标</h2>
    <p class="field-hint">
      这些结果暂时没有对应的标准指标，不会出现在趋势图中。需要长期跟踪时，可以设为本档案的临时指标并关注；
      以后同名、同单位的结果会自动归到一起。
    </p>
    <p v-if="notice" class="notice notice-success" role="status" data-testid="temp-metric-notice">{{ notice }}</p>
    <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>
    <ul class="temp-metric-list">
      <li v-for="o in unmapped" :key="o.id" class="temp-metric-item" data-testid="temp-metric-row">
        <div class="temp-metric-meta">
          <p class="queue-name">{{ o.originalName }}</p>
          <p class="queue-sub">{{ unitLabel(o) }}</p>
        </div>
        <template v-if="o.tempMetricCode">
          <span class="temp-metric-tag" data-testid="temp-metric-tag">
            临时指标{{ isFollowed(o.tempMetricCode) ? ' · 已关注' : '' }}
          </span>
          <RouterLink
            class="btn-ghost btn-small"
            :to="{ name: 'trends', query: { metric: o.tempMetricCode } }"
            data-testid="temp-metric-trend"
          >
            看趋势
          </RouterLink>
        </template>
        <button
          v-else
          type="button"
          class="btn-secondary btn-small"
          :disabled="busyId !== null"
          :aria-label="`把 ${o.originalName} 设为临时指标并关注`"
          data-testid="temp-metric-create"
          @click="makeTemp(o)"
        >
          {{ busyId === o.id ? '设置中…' : '设为临时指标并关注' }}
        </button>
      </li>
    </ul>
  </section>
</template>
