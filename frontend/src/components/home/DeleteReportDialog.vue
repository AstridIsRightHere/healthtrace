<script setup lang="ts">
// 删除未确认报告的二次确认
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
    @cancel.prevent="!deleting && emit('cancel')"
  >
    <h2 id="delete-title">删除这份报告？</h2>
    <p id="delete-desc">“{{ reportTitle }}”将被彻底删除，删除后无法恢复。</p>
    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <div class="dup-actions">
      <button class="btn-secondary" type="button" :disabled="deleting" @click="emit('cancel')">取消</button>
      <button class="btn-primary" type="button" :disabled="deleting" @click="emit('confirm')">
        {{ deleting ? '删除中…' : '确认删除' }}
      </button>
    </div>
  </dialog>
</template>
