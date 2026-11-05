<script setup lang="ts">
// 选择文件：支持多选与拖拽；不加 capture，让手机可在相册与相机间自行选择。
// 拖入的文件与点击选择走同一 pick 事件，由上传页统一做 MIME/大小校验，后端再做魔数校验。
import { ref } from 'vue'
import { ACCEPTED_TYPES } from './types'
import { dragHasFiles } from '../../utils/fileAccept'

const props = defineProps<{ disabled?: boolean; remaining: number }>()
const emit = defineEmits<{ pick: [files: File[]] }>()
const dragging = ref(false)
let depth = 0

function blocked(): boolean {
  return !!props.disabled || props.remaining <= 0
}

function onChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  if (files.length) emit('pick', files)
  // 清空以便再次选择同一文件
  target.value = ''
}

function onDragEnter(event: DragEvent) {
  if (!dragHasFiles(event.dataTransfer)) return
  event.preventDefault()
  depth++
  if (!blocked()) dragging.value = true
}

function onDragOver(event: DragEvent) {
  if (!dragHasFiles(event.dataTransfer)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = blocked() ? 'none' : 'copy'
}

function onDragLeave() {
  depth = Math.max(0, depth - 1)
  if (depth === 0) dragging.value = false
}

function onDrop(event: DragEvent) {
  if (!dragHasFiles(event.dataTransfer)) return
  event.preventDefault()
  depth = 0
  dragging.value = false
  if (blocked()) return
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length) emit('pick', files)
}
</script>

<template>
  <div
    class="photo-picker"
    data-testid="upload-dropzone"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <input
      id="report-files"
      class="visually-hidden"
      type="file"
      multiple
      :accept="ACCEPTED_TYPES"
      :disabled="disabled || remaining <= 0"
      data-testid="upload-file-input"
      @change="onChange"
    />
    <label
      for="report-files"
      class="picker-zone"
      :class="{ disabled: disabled || remaining <= 0, dragging }"
      aria-describedby="report-files-hint"
    >
      <span class="picker-icon" aria-hidden="true">＋</span>
      <span>{{ dragging ? '松开即可添加' : '选择照片或 PDF' }}</span>
      <span class="picker-sub">也可以把文件拖到这里</span>
    </label>
    <p id="report-files-hint" class="field-hint">
      支持 JPEG、PNG、HEIC、PDF，单个不超过 25 MB，每份报告最多 20 个（还可添加 {{ Math.max(remaining, 0) }} 个）
    </p>
  </div>
</template>
