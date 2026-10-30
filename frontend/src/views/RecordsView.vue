<script setup lang="ts">
// 档案（一级 Tab）：报告归档入口。本迭代只搭框架：上传入口 + 报告列表占位，
// 后续补超声 / 普通报告分类归档（PG-010 报告列表）。
import { RouterLink } from 'vue-router'
</script>

<template>
  <main class="home-page tab-page" data-testid="records-page">
    <header class="tab-header">
      <h1>档案</h1>
      <p class="field-hint">集中管理检查报告原件与识别结果。</p>
    </header>

    <section class="home-card records-upload" aria-labelledby="records-upload-title" data-testid="records-upload">
      <div>
        <h2 id="records-upload-title">上传报告</h2>
        <p class="field-hint">拍照或选择图片 / PDF，识别后逐项核对再保存。</p>
      </div>
      <RouterLink class="btn-secondary btn-small" :to="{ name: 'report-upload' }" data-testid="records-upload-link">
        <span aria-hidden="true">＋</span>&nbsp;上传报告
      </RouterLink>
    </section>

    <section class="home-card" aria-labelledby="records-list-title" data-testid="records-list">
      <div class="recent-visits-head">
        <h2 id="records-list-title">报告列表</h2>
        <RouterLink class="btn-ghost btn-small" :to="{ name: 'report-compare' }">报告对比</RouterLink>
      </div>
      <div class="placeholder-block" role="note" data-testid="records-placeholder">
        <p class="placeholder-title">报告归档正在建设中</p>
        <p class="field-hint">后续将按检验报告、超声等影像报告分类展示，可按日期筛选并查看原件。</p>
      </div>
    </section>
  </main>
</template>
