<script setup lang="ts">
// 指标胶囊多选（替代原生 <select>）：每个胶囊显示“名称 · 单位 · N 次”，未标准化项目带角标。
// 指标较多时提供搜索过滤；选择顺序即图表/表格的展示顺序。
import { computed, ref } from 'vue'
import type { MetricSummary } from '../../api/trend'

const props = withDefaults(
  defineProps<{
    metrics: MetricSummary[]
    modelValue: string[]
    max?: number
  }>(),
  { max: 6 },
)

const emit = defineEmits<{ 'update:modelValue': [codes: string[]] }>()

/** 超过这个数量才显示搜索框 */
const SEARCH_THRESHOLD = 12

const keyword = ref('')
const showSearch = computed(() => props.metrics.length > SEARCH_THRESHOLD)
const atMax = computed(() => props.modelValue.length >= props.max)

const visible = computed(() => {
  const k = keyword.value.trim().toUpperCase()
  if (!k) return props.metrics
  return props.metrics.filter((m) => m.standardName.toUpperCase().includes(k) || props.modelValue.includes(m.code))
})

function isSelected(code: string): boolean {
  return props.modelValue.includes(code)
}

function toggle(code: string) {
  if (isSelected(code)) {
    // 至少保留一个指标，避免页面空掉
    if (props.modelValue.length === 1) return
    emit('update:modelValue', props.modelValue.filter((c) => c !== code))
  } else if (!atMax.value) {
    emit('update:modelValue', [...props.modelValue, code])
  }
}

function unitOf(m: MetricSummary): string {
  return m.standardUnit || '单位未标注'
}
</script>

<template>
  <div class="metric-picker">
    <div class="metric-picker-head">
      <span class="metric-picker-title">指标</span>
      <span class="field-hint metric-picker-count" aria-live="polite">
        已选 {{ modelValue.length }}/{{ max }}<template v-if="atMax">，已达上限</template>
      </span>
    </div>

    <label v-if="showSearch" class="field metric-picker-search">
      <span class="visually-hidden">搜索指标</span>
      <input v-model="keyword" type="search" placeholder="搜索指标名称" autocomplete="off" />
    </label>

    <div class="chip-group" role="group" aria-label="选择要查看的指标，可多选">
      <button
        v-for="m in visible"
        :key="m.code"
        type="button"
        class="chip"
        :class="{ 'chip-active': isSelected(m.code), 'chip-raw': !m.standard }"
        :aria-pressed="isSelected(m.code)"
        :disabled="!isSelected(m.code) && atMax"
        :title="m.standardName"
        @click="toggle(m.code)"
      >
        <span class="chip-check" aria-hidden="true">{{ isSelected(m.code) ? '✓' : '+' }}</span>
        <span class="chip-name">{{ m.standardName }}</span>
        <span class="chip-meta">{{ unitOf(m) }} · {{ m.count }} 次</span>
        <span v-if="!m.standard" class="chip-badge">未标准化</span>
      </button>
      <p v-if="visible.length === 0" class="field-hint">没有匹配的指标。</p>
    </div>
  </div>
</template>
