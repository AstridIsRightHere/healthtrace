/**
 * 客户端预览图压缩（DEC-008）：长边缩放到 2048 px，输出 JPEG。
 * 只生成预览图，原件始终原样上传，不能以预览图替代原件。
 * HEIC、PDF 浏览器普遍无法解码，调用方应跳过（返回 null）。
 */
export const PREVIEW_MAX_EDGE = 2048
export const PREVIEW_QUALITY = 0.85

const PREVIEWABLE_TYPES = new Set(['image/jpeg', 'image/png'])

/** 是否可在浏览器内生成预览图（以 File.type 粗判，服务端仍做魔数校验） */
export function canGeneratePreview(file: File): boolean {
  return PREVIEWABLE_TYPES.has(file.type)
}

/**
 * 生成预览图；失败或类型不支持时返回 null，由上传流程只传原件。
 * createImageBitmap 的 imageOrientation: 'from-image' 会应用 EXIF 方向，避免手机照片倒置。
 */
export async function createPreview(file: File): Promise<Blob | null> {
  if (!canGeneratePreview(file) || typeof createImageBitmap !== 'function') return null
  let bitmap: ImageBitmap | null = null
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
    const scale = Math.min(1, PREVIEW_MAX_EDGE / Math.max(bitmap.width, bitmap.height))
    const width = Math.max(1, Math.round(bitmap.width * scale))
    const height = Math.max(1, Math.round(bitmap.height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    // PNG 透明区域转 JPEG 会变黑，先铺白底
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, width, height)
    ctx.drawImage(bitmap, 0, 0, width, height)

    return await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((blob) => resolve(blob), 'image/jpeg', PREVIEW_QUALITY),
    )
  } catch {
    return null
  } finally {
    bitmap?.close()
  }
}
