<script setup lang="ts">
// 首页悬浮“上传报告”按钮
import { RouterLink } from 'vue-router'
</script>

<template>
  <RouterLink class="upload-fab" :to="{ name: 'report-upload' }">
    <span aria-hidden="true">＋</span> 上传报告
  </RouterLink>
</template>
