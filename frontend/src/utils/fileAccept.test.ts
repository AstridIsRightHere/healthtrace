import { describe, expect, it } from 'vitest'
import { MAX_FILE_BYTES, checkUploadFile, dragHasFiles } from './fileAccept'

const f = (name: string, type: string, size = 1000) => ({ name, type, size })

describe('checkUploadFile', () => {
  it('accepts supported types', () => {
    expect(checkUploadFile(f('a.jpg', 'image/jpeg'))).toBeNull()
    expect(checkUploadFile(f('a.png', 'image/png'))).toBeNull()
    expect(checkUploadFile(f('a.pdf', 'application/pdf'))).toBeNull()
    expect(checkUploadFile(f('a.HEIC', ''))).toBeNull()
  })
  it('rejects unsupported or disguised types', () => {
    expect(checkUploadFile(f('x.html', 'text/html'))).toBe('type')
    expect(checkUploadFile(f('x.svg', 'image/svg+xml'))).toBe('type')
    expect(checkUploadFile(f('x.exe', ''))).toBe('type')
    // 扩展名伪装但浏览器给出真实 MIME：以 MIME 为准
    expect(checkUploadFile(f('x.heic', 'text/html'))).toBe('type')
  })
  it('rejects empty and oversized files', () => {
    expect(checkUploadFile(f('a.jpg', 'image/jpeg', 0))).toBe('empty')
    expect(checkUploadFile(f('a.jpg', 'image/jpeg', MAX_FILE_BYTES + 1))).toBe('size')
    expect(checkUploadFile(f('a.jpg', 'image/jpeg', MAX_FILE_BYTES))).toBeNull()
  })
})

describe('dragHasFiles', () => {
  it('detects file drags only', () => {
    expect(dragHasFiles({ types: ['Files'] })).toBe(true)
    expect(dragHasFiles({ types: ['text/plain'] })).toBe(false)
    expect(dragHasFiles(null)).toBe(false)
  })
})
