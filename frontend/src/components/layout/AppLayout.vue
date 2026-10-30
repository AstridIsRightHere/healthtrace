<script setup lang="ts">
// 主框架（布局路由）：四个一级 Tab + 其二级页面共用。
// 同一份 <nav>：移动端（<960px）固定在底部的 4 Tab 栏；桌面端（≥960px）变为左侧侧边栏，
// 额外显示品牌与“上传报告”主操作。核对/上传等专注流程不在此布局内（它们有自己的底部操作栏）。
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { activeTab, NAV_ITEMS } from '../../utils/navigation'
import NavIcon from './NavIcon.vue'

const route = useRoute()
const current = computed(() => activeTab(route))
</script>

<template>
  <div class="app-shell">
    <nav class="app-nav" aria-label="主导航" data-testid="app-nav">
      <p class="brand app-nav-brand">HealthTrace</p>
      <ul class="app-nav-list">
        <li v-for="item in NAV_ITEMS" :key="item.tab">
          <RouterLink
            class="app-nav-link"
            :class="{ 'is-active': current === item.tab }"
            :to="{ name: item.routeName }"
            :aria-current="current === item.tab ? 'page' : undefined"
            data-testid="app-nav-link"
            :data-tab="item.tab"
          >
            <NavIcon :name="item.tab" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </li>
      </ul>
      <RouterLink class="btn-primary app-nav-upload" :to="{ name: 'report-upload' }" data-testid="app-nav-upload">
        <span aria-hidden="true">＋</span> 上传报告
      </RouterLink>
    </nav>
    <div class="app-main" data-testid="app-main">
      <RouterView />
    </div>
  </div>
</template>
