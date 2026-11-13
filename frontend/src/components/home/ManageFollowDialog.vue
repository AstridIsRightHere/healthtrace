<script setup lang="ts">
// “管理关注”弹窗（FR-009）：勾选关注项、上下移动或拖动排序，一次保存完整有序列表。
import { computed, nextTick, onMounted, ref } from 'vue'
import { errorMessage } from '../../api'
import { followedMetricApi, type MetricDefinitionOption } from '../../api/followedMetric'
import { parseTempMetricId, tempMetricApi, type TempMetric } from '../../api/tempMetric'
import { useFollowStore } from '../../stores/follow'

const props = defineProps<{ initialCodes: string[]; saving: boolean; error: string; profileId?: number | null }>()
const emit = defineEmits<{ save: [codes: string[]]; cancel: [] }>()

const follow = useFollowStore()

const dialog = ref<HTMLDialogElement | null>(null)
const definitions = ref<MetricDefinitionOption[]>([])
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const selected = ref<string[]>([...props.initialCodes])
const dragIndex = ref<number | null>(null)

/** 本档案的临时指标（FR-009）；加载失败时只显示标准指标，不阻断管理 */
const tempMetrics = ref<TempMetric[]>([])

const nameOf = computed(
  () =>
    new Map([
      ...definitions.value.map((d) => [d.code, d.name] as [string, string]),
      ...tempMetrics.value.map((m) => [m.code, m.name] as [string, string]),
    ]),
)

async function loadDefinitions(): Promise<void> {
  loadState.value = 'loading'
  try {
    const [defs, temps] = await Promise.all([
      followedMetricApi.definitions(),
      props.profileId
        ? tempMetricApi.list(props.profileId).catch(() => [] as TempMetric[])
        : Promise.resolve([] as TempMetric[]),
    ])
    definitions.value = defs
    tempMetrics.value = temps
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

/** 临时指标删除：先二次确认；删除后从列表与已选中移除，并刷新关注数据 */
const confirmingDelete = ref<string | null>(null)
const deleting = ref(false)
const tempNotice = ref('')
const tempError = ref('')

function askDelete(code: string): void {
  tempNotice.value = ''
  tempError.value = ''
  confirmingDelete.value = code
}

async function confirmDelete(m: TempMetric): Promise<void> {
  const id = parseTempMetricId(m.code)
  if (id === null || !props.profileId || deleting.value) return
  deleting.value = true
  tempError.value = ''
  try {
    await tempMetricApi.remove(props.profileId, id)
    tempMetrics.value = tempMetrics.value.filter((t) => t.code !== m.code)
    selected.value = selected.value.filter((c) => c !== m.code)
    confirmingDelete.value = null
    tempNotice.value = `已删除临时指标“${m.name}”，相关结果仍保留在原报告中。`
    await follow.load(props.profileId, true).catch(() => undefined)
  } catch (e) {
    tempError.value = errorMessage(e, '删除失败，请稍后重试')
  } finally {
    deleting.value = false
  }
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
      <fieldset v-if="tempMetrics.length" class="follow-temp-list" data-testid="follow-manage-temp">
        <legend class="field-hint">本档案的临时指标</legend>
        <div v-for="m in tempMetrics" :key="m.code" class="follow-temp-row" data-testid="follow-temp-row">
          <label class="follow-temp-label">
            <input
              type="checkbox"
              :value="m.code"
              :checked="selected.includes(m.code)"
              :data-code="m.code"
              @change="toggle(m.code, ($event.target as HTMLInputElement).checked)"
            />
            <span class="follow-temp-name">{{ m.name }}<span class="temp-metric-tag">临时</span></span>
            <span class="follow-temp-state">{{ selected.includes(m.code) ? '已关注' : '未关注' }}</span>
          </label>
          <button
            v-if="confirmingDelete !== m.code"
            class="btn-secondary btn-danger-outline follow-temp-delete"
            type="button"
            data-testid="follow-temp-delete"
            :aria-label="`删除临时指标 ${m.name}`"
            :disabled="deleting"
            @click="askDelete(m.code)"
          >删除</button>
          <div v-else class="follow-temp-confirm" role="alert">
            <p>删除“{{ m.name }}”后会同时取消关注，相关结果回到“未归类”状态；报告和结果本身不会删除。</p>
            <div class="follow-temp-confirm-actions">
              <button
                class="btn-secondary"
                type="button"
                data-testid="follow-temp-delete-cancel"
                :disabled="deleting"
                @click="confirmingDelete = null"
              >保留</button>
              <button
                class="btn-primary btn-danger"
                type="button"
                data-testid="follow-temp-delete-confirm"
                :disabled="deleting"
                @click="confirmDelete(m)"
              >{{ deleting ? '删除中…' : '确认删除' }}</button>
            </div>
          </div>
        </div>
      </fieldset>
      <p v-if="tempNotice" class="notice" role="status" data-testid="follow-temp-notice">{{ tempNotice }}</p>
      <p v-if="tempError" class="notice notice-error" role="alert">{{ tempError }}</p>
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
