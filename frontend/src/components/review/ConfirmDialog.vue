<script setup lang="ts">
// 二次确认：确认后数据进入趋势图表
import { nextTick, onMounted, ref } from 'vue'

defineProps<{ count: number; examinationDate: string; submitting: boolean }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)

onMounted(async () => {
  await nextTick()
  dialog.value?.showModal()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="dup-dialog"
    aria-labelledby="confirm-title"
    aria-describedby="confirm-desc"
    @cancel.prevent="!submitting && emit('cancel')"
  >
    <h2 id="confirm-title">确认保存这份报告？</h2>
    <p id="confirm-desc">
      确认后数据将进入趋势图表。本次共 {{ count }} 项，检查日期 {{ examinationDate }}。
    </p>
    <div class="dup-actions">
      <button class="btn-secondary" type="button" :disabled="submitting" @click="emit('cancel')">再检查一下</button>
      <button class="btn-primary" type="button" :disabled="submitting" @click="emit('confirm')">
        {{ submitting ? '保存中…' : '确认保存' }}
      </button>
    </div>
  </dialog>
</template>
