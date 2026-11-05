<script setup lang="ts">
// 报告封面缩略图：图片显示预览图；PDF 没有可渲染的预览，显示 PDF 图标 + 文件名；无文件显示通用占位。
import { computed } from 'vue'
import { reportApi, type ReportListItem } from '../../api/report'
import { coverKind } from '../../utils/reportCover'

const props = defineProps<{
  report: Pick<ReportListItem, 'coverPhotoId' | 'coverHasPreview' | 'coverMediaType' | 'coverFileName'>
}>()

const kind = computed(() => coverKind(props.report))
</script>

<template>
  <img
    v-if="kind === 'image' && report.coverPhotoId"
    class="queue-thumb"
    :src="reportApi.photoUrl(report.coverPhotoId, 'preview')"
    alt=""
    loading="lazy"
    data-testid="report-cover-image"
  />
  <span
    v-else-if="kind === 'pdf'"
    class="queue-thumb queue-thumb-pdf"
    :title="report.coverFileName || 'PDF 文件'"
    data-testid="report-cover-pdf"
  >
    <svg class="pdf-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M6 2h8l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" fill="none" stroke="currentColor" stroke-width="1.6" />
      <path d="M14 2v5h5" fill="none" stroke="currentColor" stroke-width="1.6" />
    </svg>
    <span class="pdf-label">PDF</span>
    <span class="pdf-name">{{ report.coverFileName || 'PDF 文件' }}</span>
  </span>
  <span v-else class="queue-thumb queue-thumb-placeholder" aria-hidden="true" data-testid="report-cover-placeholder">报告</span>
</template>
