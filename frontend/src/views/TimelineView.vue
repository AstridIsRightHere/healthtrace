<script setup lang="ts">
// 时间线（一级 Tab）：全局就医时间轴。本迭代只搭框架：就诊记录入口 + 时间轴占位，
// 后续补就诊与报告的深度绑定（PG-011 / FR-013）。
import { RouterLink } from 'vue-router'
</script>

<template>
  <main class="home-page tab-page" data-testid="timeline-page">
    <header class="tab-header">
      <h1>时间线</h1>
      <p class="field-hint">按时间回顾每一次就诊与检查。</p>
    </header>

    <section class="home-card" aria-labelledby="timeline-visits-title" data-testid="timeline-visits">
      <div class="recent-visits-head">
        <h2 id="timeline-visits-title">就诊记录</h2>
        <RouterLink class="btn-secondary btn-small" :to="{ name: 'visits' }" data-testid="timeline-visits-link">
          管理就诊
        </RouterLink>
      </div>
      <p class="field-hint">记录就诊日期、医院与科室，并关联当次的检查报告。</p>
    </section>

    <section class="home-card" aria-labelledby="timeline-axis-title" data-testid="timeline-axis">
      <h2 id="timeline-axis-title">就医时间轴</h2>
      <div class="placeholder-block" role="note" data-testid="timeline-placeholder">
        <p class="placeholder-title">全局时间轴正在建设中</p>
        <p class="field-hint">后续将把就诊与报告按日期串成一条时间轴，点击即可查看当次的报告与指标。</p>
      </div>
    </section>
  </main>
</template>
