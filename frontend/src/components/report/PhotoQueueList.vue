<script setup lang="ts">
// 上传队列：缩略图、排序、移除、单文件状态与重试
import type { QueueItem, QueueStatus } from './types'

defineProps<{ items: QueueItem[]; locked: boolean }>()
const emit = defineEmits<{
  move: [index: number, delta: number]
  remove: [index: number]
  retry: [index: number]
}>()

const statusText: Record<QueueStatus, string> = {
  waiting: '待上传',
  preparing: '处理中',
  uploading: '上传中',
  done: '已上传',
  duplicate: '疑似重复',
  skipped: '已跳过',
  failed: '上传失败',
}

function kindLabel(file: File): string {
  if (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')) return 'PDF'
  return 'HEIC'
}

function sizeText(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`
}
</script>

<template>
  <ol class="queue-list" aria-label="待上传文件">
    <li v-for="(item, index) in items" :key="item.key" class="queue-item" :class="`is-${item.status}`">
      <img v-if="item.thumbUrl" class="queue-thumb" :src="item.thumbUrl" alt="" />
      <span v-else class="queue-thumb queue-thumb-placeholder" aria-hidden="true">{{ kindLabel(item.file) }}</span>

      <div class="queue-meta">
        <p class="queue-name">第 {{ index + 1 }} 页 · {{ item.file.name }}</p>
        <p class="queue-sub">
          {{ sizeText(item.file.size) }} ·
          <span role="status">
            {{ statusText[item.status] }}<template v-if="item.status === 'uploading'"> {{ item.progress }}%</template>
          </span>
        </p>
        <progress
          v-if="item.status === 'uploading'"
          class="queue-progress"
          max="100"
          :value="item.progress"
          :aria-label="`${item.file.name} 上传进度`"
        />
        <p v-if="item.error" class="field-error">{{ item.error }}</p>
      </div>

      <div class="queue-actions">
        <template v-if="!locked">
          <button
            class="icon-btn"
            type="button"
            :disabled="index === 0"
            :aria-label="`上移 ${item.file.name}`"
            @click="emit('move', index, -1)"
          >
            ↑
          </button>
          <button
            class="icon-btn"
            type="button"
            :disabled="index === items.length - 1"
            :aria-label="`下移 ${item.file.name}`"
            @click="emit('move', index, 1)"
          >
            ↓
          </button>
          <button class="icon-btn" type="button" :aria-label="`移除 ${item.file.name}`" @click="emit('remove', index)">
            ✕
          </button>
        </template>
        <button
          v-else-if="item.status === 'failed'"
          class="btn-secondary btn-small"
          type="button"
          @click="emit('retry', index)"
        >
          重试
        </button>
      </div>
    </li>
  </ol>
</template>
