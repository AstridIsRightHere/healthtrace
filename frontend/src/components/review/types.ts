/** 草稿页可编辑行：key 仅用于前端渲染，id 为空表示新增行 */
export interface EditableRow {
  key: string
  id: number | null
  originalName: string
  originalValue: string
  unitText: string
  referenceText: string
  sourcePhotoId: number | null
  /** OCR 实际置信度；用户修改该行后视为人工确认，不再展示 */
  confidence: number | null
}

/** 低于该置信度的行提示“请重点核对” */
export const LOW_CONFIDENCE = 0.8
