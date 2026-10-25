<script setup lang="ts">
// 疑似重复提示（FR-005）：同档已存在相同文件时由用户决定仍然上传或跳过
import { nextTick, onMounted, ref } from 'vue'

defineProps<{ fileName: string; existingCount: number }>()
const emit = defineEmits<{ keep: []; skip: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)

onMounted(async () => {
  await nextTick()
  // showModal 提供焦点限制与 Esc 关闭；Esc 视为跳过
  dialog.value?.showModal()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="dup-dialog"
    aria-labelledby="dup-title"
    aria-describedby="dup-desc"
    @cancel.prevent="emit('skip')"
  >
    <h2 id="dup-title">这张图片可能已经上传过</h2>
    <p id="dup-desc">
      「{{ fileName }}」与档案中 {{ existingCount }} 份已有报告里的文件相同。要仍然上传吗？
    </p>
    <div class="dup-actions">
      <button class="btn-secondary" type="button" @click="emit('skip')">跳过</button>
      <button class="btn-primary" type="button" @click="emit('keep')">仍然上传</button>
    </div>
  </dialog>
</template>
