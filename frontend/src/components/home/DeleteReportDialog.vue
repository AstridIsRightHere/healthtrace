<script setup lang="ts">
// 彻底删除报告的二次确认（FR-016）：明确告知原件与全部分析数据将被永久移除
import { nextTick, onMounted, ref } from 'vue'

defineProps<{ reportTitle: string; deleting: boolean; error: string }>()
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
    aria-labelledby="delete-title"
    aria-describedby="delete-desc"
    data-testid="purge-dialog"
    @cancel.prevent="!deleting && emit('cancel')"
  >
    <h2 id="delete-title">彻底删除这份报告？</h2>
    <p id="delete-desc">
      “{{ reportTitle }}”的<strong>原件和所有分析数据将被永久移除</strong>，包括原图、预览图、识别结果、检验指标、修改记录和就诊关联。删除后无法恢复，趋势图和报告对比中也不再出现。
    </p>
    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <div class="dup-actions">
      <button class="btn-secondary" type="button" :disabled="deleting" @click="emit('cancel')">取消</button>
      <button
        class="btn-primary btn-danger"
        type="button"
        :disabled="deleting"
        data-testid="purge-confirm"
        @click="emit('confirm')"
      >
        {{ deleting ? '删除中…' : '永久删除' }}
      </button>
    </div>
  </dialog>
</template>
