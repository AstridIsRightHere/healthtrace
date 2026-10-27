<script setup lang="ts">
// 自定义报告下拉（替代原生 select）：按钮 + 列表框，支持键盘 ↑↓/Enter/Esc
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ReportListItem } from '../../api/report'
import { reportLabel } from '../../utils/reportCompare'

const props = defineProps<{
  label: string
  reports: ReportListItem[]
  modelValue: number | null
  /** 不可选的报告（另一侧已选） */
  disabledId?: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()

const open = ref(false)
const activeIndex = ref(-1)
const root = ref<HTMLElement | null>(null)
const listRef = ref<HTMLElement | null>(null)
const uid = `report-select-${Math.random().toString(36).slice(2, 8)}`

const selected = computed(() => props.reports.find((r) => r.id === props.modelValue) ?? null)

function toggle() {
  open.value = !open.value
  if (open.value) {
    activeIndex.value = Math.max(0, props.reports.findIndex((r) => r.id === props.modelValue))
    void nextTick(() => listRef.value?.focus())
  }
}

function choose(r: ReportListItem) {
  if (r.id === props.disabledId) return
  emit('update:modelValue', r.id)
  open.value = false
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    open.value = false
    return
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const step = e.key === 'ArrowDown' ? 1 : -1
    const n = props.reports.length
    if (n) activeIndex.value = (activeIndex.value + step + n) % n
  } else if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    const r = props.reports[activeIndex.value]
    if (r) choose(r)
  }
}

function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div ref="root" class="report-select">
    <span :id="`${uid}-label`" class="report-select-label">{{ label }}</span>
    <button
      type="button"
      class="report-select-trigger"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-labelledby="`${uid}-label ${uid}-value`"
      @click="toggle"
    >
      <span :id="`${uid}-value`" class="report-select-value">
        {{ selected ? reportLabel(selected) : '请选择报告' }}
      </span>
      <span class="report-select-caret" aria-hidden="true">▾</span>
    </button>
    <ul
      v-if="open"
      ref="listRef"
      class="report-select-list"
      role="listbox"
      tabindex="-1"
      :aria-labelledby="`${uid}-label`"
      :aria-activedescendant="activeIndex >= 0 ? `${uid}-opt-${activeIndex}` : undefined"
      @keydown="onKeydown"
    >
      <li
        v-for="(r, i) in reports"
        :id="`${uid}-opt-${i}`"
        :key="r.id"
        role="option"
        class="report-select-option"
        :class="{ active: i === activeIndex, selected: r.id === modelValue }"
        :aria-selected="r.id === modelValue"
        :aria-disabled="r.id === disabledId"
        @click="choose(r)"
        @mouseenter="activeIndex = i"
      >
        {{ reportLabel(r) }}
        <span v-if="r.id === disabledId" class="field-hint">（已在另一侧选择）</span>
      </li>
    </ul>
  </div>
</template>
