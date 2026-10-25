<script setup lang="ts">
// 可编辑指标表（FR-007）：名称、数值、单位、参考范围；低置信度行黄色边框提示重点核对，不伪造置信度
import { LOW_CONFIDENCE, type EditableRow } from './types'

defineProps<{
  rows: EditableRow[]
  errors: Record<string, string>
  disabled: boolean
}>()
const emit = defineEmits<{ edit: [index: number]; remove: [index: number]; add: [] }>()

function lowConfidence(row: EditableRow): boolean {
  return row.confidence !== null && row.confidence < LOW_CONFIDENCE
}

function err(errors: Record<string, string>, index: number, field: string): string {
  return errors[`observations[${index}].${field}`] ?? ''
}
</script>

<template>
  <section class="obs-section" aria-labelledby="obs-title">
    <h2 id="obs-title">检验项目</h2>
    <p v-if="rows.length === 0" class="field-hint">还没有项目，点击下方“添加一项”开始录入。</p>

    <ul class="obs-list">
      <li
        v-for="(row, index) in rows"
        :key="row.key"
        class="obs-row"
        :class="{ 'is-low-confidence': lowConfidence(row) }"
      >
        <p v-if="lowConfidence(row)" class="obs-warning" role="note">
          <span aria-hidden="true">⚠</span> 识别置信度较低，请重点核对
        </p>
        <div class="obs-grid">
          <div class="field">
            <label :for="`obs-name-${row.key}`">项目名称</label>
            <input
              :id="`obs-name-${row.key}`"
              v-model="row.originalName"
              type="text"
              maxlength="160"
              :disabled="disabled"
              :aria-invalid="err(errors, index, 'originalName') ? 'true' : undefined"
              @input="emit('edit', index)"
            />
            <p v-if="err(errors, index, 'originalName')" class="field-error">{{ err(errors, index, 'originalName') }}</p>
          </div>
          <div class="field">
            <label :for="`obs-value-${row.key}`">结果</label>
            <input
              :id="`obs-value-${row.key}`"
              v-model="row.originalValue"
              type="text"
              maxlength="160"
              placeholder="如 6.5、<0.5、阴性"
              :disabled="disabled"
              :aria-invalid="err(errors, index, 'originalValue') ? 'true' : undefined"
              @input="emit('edit', index)"
            />
            <p v-if="err(errors, index, 'originalValue')" class="field-error">{{ err(errors, index, 'originalValue') }}</p>
          </div>
          <div class="field">
            <label :for="`obs-unit-${row.key}`">单位</label>
            <input
              :id="`obs-unit-${row.key}`"
              v-model="row.unitText"
              type="text"
              maxlength="80"
              :disabled="disabled"
              @input="emit('edit', index)"
            />
            <p v-if="err(errors, index, 'unitText')" class="field-error">{{ err(errors, index, 'unitText') }}</p>
          </div>
          <div class="field">
            <label :for="`obs-ref-${row.key}`">参考范围</label>
            <input
              :id="`obs-ref-${row.key}`"
              v-model="row.referenceText"
              type="text"
              maxlength="160"
              placeholder="如 3.5-9.5"
              :disabled="disabled"
              @input="emit('edit', index)"
            />
            <p v-if="err(errors, index, 'referenceText')" class="field-error">{{ err(errors, index, 'referenceText') }}</p>
          </div>
        </div>
        <button
          type="button"
          class="btn-link obs-remove"
          :disabled="disabled"
          :aria-label="`删除第 ${index + 1} 项 ${row.originalName}`"
          @click="emit('remove', index)"
        >
          删除此项
        </button>
      </li>
    </ul>

    <button type="button" class="btn-secondary" :disabled="disabled" @click="emit('add')">+ 添加一项</button>
  </section>
</template>
