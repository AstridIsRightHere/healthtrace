<script setup lang="ts">
// 选择文件：支持多选；不加 capture，让手机可在相册与相机间自行选择
import { ACCEPTED_TYPES } from './types'

defineProps<{ disabled?: boolean; remaining: number }>()
const emit = defineEmits<{ pick: [files: File[]] }>()

function onChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  if (files.length) emit('pick', files)
  // 清空以便再次选择同一文件
  target.value = ''
}
</script>

<template>
  <div class="photo-picker">
    <input
      id="report-files"
      class="visually-hidden"
      type="file"
      multiple
      :accept="ACCEPTED_TYPES"
      :disabled="disabled || remaining <= 0"
      @change="onChange"
    />
    <label
      for="report-files"
      class="picker-zone"
      :class="{ disabled: disabled || remaining <= 0 }"
      aria-describedby="report-files-hint"
    >
      <span class="picker-icon" aria-hidden="true">＋</span>
      <span>选择照片或 PDF</span>
    </label>
    <p id="report-files-hint" class="field-hint">
      支持 JPEG、PNG、HEIC、PDF，单个不超过 25 MB，每份报告最多 20 个（还可添加 {{ Math.max(remaining, 0) }} 个）
    </p>
  </div>
</template>
