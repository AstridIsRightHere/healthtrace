<script setup lang="ts">
// OCR 原始文本（FR-014）：默认折叠，供核对时参考
defineProps<{ text: string }>()
</script>

<template>
  <details class="raw-text">
    <summary>查看 OCR 原始文本</summary>
    <pre>{{ text }}</pre>
  </details>
</template>
