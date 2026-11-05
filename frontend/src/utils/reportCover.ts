// 报告封面展示类型：PDF 无法作为 <img> 渲染（之前列表里显示为空白），需单独走占位。
import type { ReportListItem } from '../api/report'

export type CoverKind = 'image' | 'pdf' | 'none'

export function coverKind(
  r: Pick<ReportListItem, 'coverPhotoId' | 'coverHasPreview' | 'coverMediaType' | 'coverFileName'>,
): CoverKind {
  if (!r.coverPhotoId) return 'none'
  const mime = (r.coverMediaType ?? '').toLowerCase()
  if (mime === 'application/pdf' || (!mime && /\.pdf$/i.test(r.coverFileName ?? ''))) return 'pdf'
  return r.coverHasPreview ? 'image' : 'none'
}
