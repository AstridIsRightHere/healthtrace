<script setup lang="ts">
// 通用图表容器：onMounted 初始化、onBeforeUnmount 销毁，option 变化时增量 setOption，容器尺寸变化时 resize
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { EChartsType } from 'echarts/core'
import { echarts, type ChartOption } from '../../utils/echarts'

const props = withDefaults(
  defineProps<{
    option: ChartOption
    height?: string
    /** 图表的文字说明，供读屏使用；完整数据见图下表格 */
    ariaLabel?: string
  }>(),
  { height: '320px', ariaLabel: '趋势图' },
)

const el = ref<HTMLDivElement | null>(null)
const chart = shallowRef<EChartsType | null>(null)
let observer: ResizeObserver | null = null

function resize() {
  chart.value?.resize()
}

onMounted(() => {
  if (!el.value) return
  chart.value = echarts.init(el.value)
  chart.value.setOption(props.option)
  window.addEventListener('resize', resize)
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(resize)
    observer.observe(el.value)
  }
})

watch(
  () => props.option,
  (option) => {
    // notMerge:false 保留动画；series 数量变化时用 replaceMerge 移除多余的旧 series
    chart.value?.setOption(option, { notMerge: false, replaceMerge: ['series'] })
  },
  { deep: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div ref="el" class="base-chart" role="img" :aria-label="ariaLabel" :style="{ height }" />
</template>

<style scoped>
.base-chart {
  width: 100%;
}
</style>
