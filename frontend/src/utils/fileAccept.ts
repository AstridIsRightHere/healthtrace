// 上传文件前端预校验（FR-004）：只做 MIME/扩展名与大小的快速拦截，真正的类型判定以后端魔数校验为准。
export const MAX_FILE_BYTES = 25 * 1024 * 1024

const ACCEPTED_MIME = new Set(['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/pdf'])
/** 部分浏览器/系统对 HEIC 不给 MIME（空串），此时才退回扩展名判断 */
const HEIC_EXT = /\.(heic|heif)$/i

export type FileRejectReason = 'type' | 'size' | 'empty'

export function checkUploadFile(file: Pick<File, 'name' | 'type' | 'size'>): FileRejectReason | null {
  const mime = file.type.toLowerCase()
  const typeOk = mime ? ACCEPTED_MIME.has(mime) : HEIC_EXT.test(file.name)
  if (!typeOk) return 'type'
  if (file.size <= 0) return 'empty'
  if (file.size > MAX_FILE_BYTES) return 'size'
  return null
}

export const REJECT_TEXT: Record<FileRejectReason, string> = {
  type: '格式不支持',
  size: '超过 25 MB',
  empty: '文件为空',
}

/** 拖拽事件是否携带文件（拖动页面文字/链接时不进入高亮态） */
export function dragHasFiles(dt: Pick<DataTransfer, 'types'> | null): boolean {
  return !!dt && Array.from(dt.types).includes('Files')
}
