<script setup lang="ts">
// 主导航线性图标（24px，描边随文字颜色）；装饰性，文字标签始终可见
import type { NavTab } from '../../utils/navigation'

defineProps<{ name: NavTab }>()
</script>

<template>
  <svg
    class="app-nav-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <template v-if="name === 'home'">
      <path d="M4 10.5 12 4l8 6.5" />
      <path d="M6 9v10a1 1 0 0 0 1 1h3.5v-5h3v5H17a1 1 0 0 0 1-1V9" />
    </template>
    <template v-else-if="name === 'records'">
      <path d="M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" />
      <path d="M9 13h6" />
    </template>
    <template v-else-if="name === 'timeline'">
      <path d="M8 5v14" />
      <circle cx="8" cy="7" r="2" />
      <circle cx="8" cy="17" r="2" />
      <path d="M12 7h7M12 17h7M12 12h5" />
    </template>
    <template v-else>
      <circle cx="12" cy="8.5" r="3.5" />
      <path d="M5 20a7 7 0 0 1 14 0" />
    </template>
  </svg>
</template>
