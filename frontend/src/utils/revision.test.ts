import { describe, expect, it } from 'vitest'
import type { ObservationView } from '../api/ocr'
import type { RevisionView } from '../api/revision'
import type { EditableRow } from '../components/review/types'
import {
  buildRevisePayload,
  formatChange,
  formatDateChange,
  formatRevisionTime,
  formatVersions,
  hasNoChange,
  sortRevisions,
  toObservationInputs,
} from './revision'

const saved = (id: number, name: string, value: string, unit: string | null = '10^9/L'): ObservationView => ({
  id,
  originalName: name,
  originalValue: value,
  unitText: unit,
  referenceText: '3.5-9.5',
  resultKind: 'EXACT',
  abnormalFlag: null,
  confidence: null,
  sourcePhotoId: 1,
  rowOrder: id,
})

const row = (id: number | null, name: string, value: string, unit = '10^9/L', ref = '3.5-9.5'): EditableRow => ({
  key: `k${id ?? 'new'}`,
  id,
  originalName: name,
  originalValue: value,
  unitText: unit,
  referenceText: ref,
  sourcePhotoId: id === null ? null : 1,
  confidence: null,
})

const snap = (name: string, value: string, unit: string | null = '10^9/L', ref: string | null = null) => ({
  originalName: name,
  originalValue: value,
  unitText: unit,
  referenceText: ref,
})

const revision = (id: number, createdAt: string): RevisionView => ({
  id,
  fromVersion: id,
  toVersion: id + 1,
  reason: null,
  createdAt,
  examinationDate: null,
  changes: [],
})

describe('toObservationInputs / buildRevisePayload', () => {
  it('去掉首尾空白，空单位/参考范围为 null，新增行不带 id', () => {
    expect(toObservationInputs([row(null, ' 血糖 ', ' 5.6 ', ' ', '')])).toEqual([
      { id: undefined, originalName: '血糖', originalValue: '5.6', unitText: null, referenceText: null, sourcePhotoId: null },
    ])
  })

  it('原因为空白时提交 null，否则去掉首尾空白', () => {
    expect(buildRevisePayload(3, '2026-01-02', '  ', []).reason).toBeNull()
    const payload = buildRevisePayload(3, '2026-01-02', ' 抄错了 ', [row(1, '白细胞', '6.1')])
    expect(payload).toMatchObject({ version: 3, examinationDate: '2026-01-02', reason: '抄错了' })
    expect(payload.observations[0]).toMatchObject({ id: 1, originalValue: '6.1' })
  })
})

describe('hasNoChange', () => {
  const list = [saved(1, '白细胞', '6.1'), saved(2, '红细胞', '4.5')]

  it('内容一致（仅空白差异、顺序不同）视为无改动', () => {
    expect(hasNoChange(list, '2026-01-02', [row(2, '红细胞', '4.5 '), row(1, ' 白细胞', '6.1')], '2026-01-02')).toBe(true)
  })

  it('数值、日期、新增、删除都算改动', () => {
    expect(hasNoChange(list, '2026-01-02', [row(1, '白细胞', '7.2'), row(2, '红细胞', '4.5')], '2026-01-02')).toBe(false)
    expect(hasNoChange(list, '2026-01-02', [row(1, '白细胞', '6.1'), row(2, '红细胞', '4.5')], '2026-01-03')).toBe(false)
    expect(hasNoChange(list, '2026-01-02', [row(1, '白细胞', '6.1')], '2026-01-02')).toBe(false)
    expect(
      hasNoChange(list, '2026-01-02', [row(1, '白细胞', '6.1'), row(null, '红细胞', '4.5')], '2026-01-02'),
    ).toBe(false)
  })

  it('单位从 null 改为空串不算改动', () => {
    expect(hasNoChange([saved(1, '尿蛋白', '阴性', null)], null, [row(1, '尿蛋白', '阴性', '')], '')).toBe(true)
  })
})

describe('formatChange', () => {
  it('修改：单位相同只写一次', () => {
    expect(formatChange({ type: 'UPDATED', before: snap('白细胞', '6.1'), after: snap('白细胞', '7.2') })).toBe(
      '修改 白细胞 6.1 → 7.2 10^9/L',
    )
  })

  it('修改：名称、单位、参考范围变化都展示', () => {
    expect(
      formatChange({
        type: 'UPDATED',
        before: snap('血糖', '100', 'mg/dL', null),
        after: snap('空腹血糖', '5.6', 'mmol/L', '3.9-6.1'),
      }),
    ).toBe('修改 血糖 → 空腹血糖 100 mg/dL → 5.6 mmol/L（参考范围 无 → 3.9-6.1）')
  })

  it('新增与删除', () => {
    expect(formatChange({ type: 'ADDED', before: null, after: snap('尿蛋白', '阴性', null) })).toBe('新增 尿蛋白 阴性')
    expect(formatChange({ type: 'REMOVED', before: snap('白细胞', '6.1'), after: null })).toBe(
      '删除 白细胞 6.1 10^9/L',
    )
  })
})

describe('修改记录展示', () => {
  it('检查日期变更', () => {
    expect(formatDateChange({ before: '2026-01-02', after: '2026-01-03' })).toBe('检查日期 2026-01-02 → 2026-01-03')
    expect(formatDateChange(null)).toBe('')
    expect(formatDateChange({ before: '2026-01-02', after: '2026-01-02' })).toBe('')
  })

  it('版本文案', () => {
    expect(formatVersions({ fromVersion: 2, toVersion: 3 })).toBe('第 2 版 → 第 3 版')
  })

  it('UTC 时间按本地时间格式化，非法时间返回空串', () => {
    expect(formatRevisionTime('2026-01-02T03:04:05')).not.toBe('')
    expect(formatRevisionTime('bad')).toBe('')
  })

  it('最新的修改在前', () => {
    const sorted = sortRevisions([
      revision(1, '2026-01-01T00:00:00'),
      revision(3, '2026-01-03T00:00:00'),
      revision(2, '2026-01-03T00:00:00'),
    ])
    expect(sorted.map((r) => r.id)).toEqual([3, 2, 1])
  })
})
