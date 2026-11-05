<script setup lang="ts">
// 时间线（一级 Tab）：就诊与未关联就诊的报告合并成一条时间轴，按年分组、日期倒序（FR-013、任务三）。
// 就诊卡片下展示当次关联的报告；报告点击进入核对/查看页。
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { errorMessage } from '../api'
import { visitApi, type TimelineEntry } from '../api/visit'
import { useProfileStore } from '../stores/profile'
import { conclusionSummary, groupTimelineByYear, reportKindLabel, type TimelineYearGroup } from '../utils/reportKind'
import { formatShortDate, visitTitle } from '../utils/visit'

const profile = useProfileStore()
const groups = ref<TimelineYearGroup[]>([])
const loading = ref(true)
const message = ref('')

async function load() {
  const profileId = profile.profile?.id
  if (!profileId) {
    loading.value = false
    return
  }
  loading.value = true
  message.value = ''
  try {
    const entries: TimelineEntry[] = await visitApi.timeline(profileId, 100)
    groups.value = groupTimelineByYear(entries)
  } catch (error) {
    message.value = errorMessage(error, '时间线加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function entryKey(entry: TimelineEntry): string {
  return entry.type === 'VISIT' ? `v${entry.visit?.id}` : `r${entry.report?.id}`
}

onMounted(load)
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

      <p v-if="loading" class="field-hint" role="status">加载中…</p>
      <div v-else-if="message" class="notice notice-error" role="alert">
        {{ message }}
        <button class="btn-ghost btn-small" type="button" @click="load">重试</button>
      </div>
      <div v-else-if="groups.length === 0" class="placeholder-block" role="note" data-testid="timeline-empty">
        <p class="placeholder-title">还没有就诊或报告</p>
        <p class="field-hint">添加一次就诊，或上传报告后，这里会按日期串成时间轴。</p>
      </div>

      <div v-else class="timeline-axis">
        <section v-for="group in groups" :key="group.year" class="timeline-year" data-testid="timeline-year">
          <h3 class="timeline-year-title">{{ group.year }}</h3>
          <ol class="timeline-list">
            <li
              v-for="entry in group.entries"
              :key="entryKey(entry)"
              class="timeline-item"
              :class="entry.type === 'VISIT' ? 'timeline-item-visit' : 'timeline-item-report'"
              :data-testid="entry.type === 'VISIT' ? 'timeline-visit' : 'timeline-report'"
            >
              <p class="timeline-date">{{ entry.date ? formatShortDate(entry.date) : '日期未填写' }}</p>

              <div v-if="entry.type === 'VISIT' && entry.visit" class="timeline-body">
                <p class="timeline-title">
                  <span class="timeline-badge">就诊</span>
                  {{ visitTitle(entry.visit) }}
                </p>
                <p v-if="entry.visit.note" class="field-hint">{{ conclusionSummary(entry.visit.note) }}</p>
                <ul v-if="entry.visit.reports.length" class="timeline-linked">
                  <li v-for="r in entry.visit.reports" :key="r.id">
                    <RouterLink
                      class="timeline-report-link"
                      :to="{ name: 'report-review', params: { reportId: String(r.id) } }"
                    >
                      {{ reportKindLabel(r.reportKind) }} · {{ r.title?.trim() || '未命名报告' }}
                    </RouterLink>
                    <p v-if="r.conclusionText" class="field-hint">{{ conclusionSummary(r.conclusionText) }}</p>
                  </li>
                </ul>
                <p v-else class="field-hint">未关联报告</p>
              </div>

              <div v-else-if="entry.report" class="timeline-body">
                <p class="timeline-title">
                  <span class="timeline-badge timeline-badge-report">{{ reportKindLabel(entry.report.reportKind) }}</span>
                  <RouterLink
                    class="timeline-report-link"
                    :to="{ name: 'report-review', params: { reportId: String(entry.report.id) } }"
                  >
                    {{ entry.report.title?.trim() || '未命名报告' }}
                  </RouterLink>
                </p>
                <p v-if="entry.report.conclusionText" class="field-hint">
                  {{ conclusionSummary(entry.report.conclusionText) }}
                </p>
              </div>
            </li>
          </ol>
        </section>
      </div>
    </section>
  </main>
</template>
