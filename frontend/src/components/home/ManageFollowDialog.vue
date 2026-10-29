<script setup lang="ts">
// “管理关注”弹窗（FR-009）：勾选关注项、上下移动或拖动排序，一次保存完整有序列表。
import { computed, nextTick, onMounted, ref } from 'vue'
import { followedMetricApi, type MetricDefinitionOption } from '../../api/followedMetric'

const props = defineProps<{ initialCodes: string[]; saving: boolean; error: string }>()
const emit = defineEmits<{ save: [codes: string[]]; cancel: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)
const definitions = ref<MetricDefinitionOption[]>([])
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const selected = ref<string[]>([...props.initialCodes])
const dragIndex = ref<number | null>(null)

const nameOf = computed(() => new Map(definitions.value.map((d) => [d.code, d.name])))

async function loadDefinitions(): Promise<void> {
  loadState.value = 'loading'
  try {
    definitions.value = await followedMetricApi.definitions()
    loadState.value = 'ready'
  } catch {
    loadState.value = 'error'
  }
}

onMounted(async () => {
  await nextTick()
  dialog.value?.showModal()
  await loadDefinitions()
})

function toggle(code: string, checked: boolean): void {
  if (checked) {
    if (!selected.value.includes(code)) selected.value = [...selected.value, code]
  } else {
    selected.value = selected.value.filter((c) => c !== code)
  }
}

function move(from: number, to: number): void {
  if (to < 0 || to >= selected.value.length || from === to) return
  const next = [...selected.value]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item as string)
  selected.value = next
}

function onDragStart(index: number): void {
  dragIndex.value = index
}

function onDrop(index: number): void {
  if (dragIndex.value !== null) move(dragIndex.value, index)
  dragIndex.value = null
}
</script>

<template>
  <dialog
    ref="dialog"
    class="dup-dialog follow-manage"
    aria-labelledby="follow-manage-title"
    data-testid="follow-manage-dialog"
    @cancel.prevent="!saving && emit('cancel')"
  >
    <h2 id="follow-manage-title">管理关注</h2>

    <p v-if="loadState === 'loading'" class="field-hint" role="status">加载中…</p>
    <div v-else-if="loadState === 'error'" class="notice notice-error" role="alert">
      指标列表加载失败。
      <button class="btn-secondary" type="button" @click="loadDefinitions">请点击重试</button>
    </div>
    <template v-else>
      <h3 class="field-hint">已关注（拖动或用按钮调整顺序）</h3>
      <p v-if="selected.length === 0" class="field-hint">尚未关注任何指标</p>
      <ol v-else class="follow-manage-order" data-testid="follow-manage-order">
        <li
          v-for="(code, i) in selected"
          :key="code"
          draggable="true"
          :data-code="code"
          @dragstart="onDragStart(i)"
          @dragover.prevent
          @drop.prevent="onDrop(i)"
        >
          <span>{{ nameOf.get(code) ?? code }}</span>
          <button
            class="btn-secondary"
            type="button"
            :disabled="i === 0"
            :aria-label="`上移 ${nameOf.get(code) ?? code}`"
            @click="move(i, i - 1)"
          >↑</button>
          <button
            class="btn-secondary"
            type="button"
            :disabled="i === selected.length - 1"
            :aria-label="`下移 ${nameOf.get(code) ?? code}`"
            @click="move(i, i + 1)"
          >↓</button>
        </li>
      </ol>

      <fieldset class="follow-manage-options">
        <legend class="field-hint">可关注的指标</legend>
        <label v-for="d in definitions" :key="d.code">
          <input
            type="checkbox"
            :value="d.code"
            :checked="selected.includes(d.code)"
            :data-code="d.code"
            @change="toggle(d.code, ($event.target as HTMLInputElement).checked)"
          />
          {{ d.name }}
        </label>
      </fieldset>
    </template>

    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <div class="dup-actions">
      <button class="btn-secondary" type="button" :disabled="saving" @click="emit('cancel')">取消</button>
      <button
        class="btn-primary"
        type="button"
        data-testid="follow-manage-save"
        :disabled="saving || loadState !== 'ready'"
        @click="emit('save', [...selected])"
      >
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </div>
  </dialog>
</template>
