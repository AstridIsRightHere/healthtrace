import { describe, expect, it } from 'vitest'
import { coverKind } from './reportCover'

const base = { coverPhotoId: 1, coverHasPreview: true, coverMediaType: 'image/jpeg', coverFileName: 'a.jpg' }

describe('coverKind', () => {
  it('shows image thumbnails when a preview exists', () => {
    expect(coverKind(base)).toBe('image')
  })

  it('uses the PDF placeholder for PDFs even if a preview flag is set', () => {
    expect(coverKind({ ...base, coverMediaType: 'application/pdf', coverFileName: 'r.pdf' })).toBe('pdf')
    expect(coverKind({ ...base, coverMediaType: 'APPLICATION/PDF' })).toBe('pdf')
  })

  it('falls back to the file extension when MIME is missing', () => {
    expect(coverKind({ ...base, coverMediaType: null, coverFileName: '报告.PDF' })).toBe('pdf')
  })

  it('returns none without a cover or preview', () => {
    expect(coverKind({ ...base, coverPhotoId: null })).toBe('none')
    expect(coverKind({ ...base, coverHasPreview: false })).toBe('none')
  })
})
