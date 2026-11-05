import { describe, expect, it } from 'vitest'
import { TITLE_MAX_LENGTH, metaUnchanged, toMetaPayload, validateReportMeta } from './reportMeta'

const today = '2026-11-05'
const draft = { processingStatus: 'PENDING_CONFIRMATION' as const, reportKind: 'STRUCTURED' as const }
const confirmedLab = { processingStatus: 'CONFIRMED' as const, reportKind: 'STRUCTURED' as const }
const confirmedImaging = { processingStatus: 'CONFIRMED' as const, reportKind: 'IMAGING' as const }

describe('validateReportMeta', () => {
  it('accepts a normal title and past date', () => {
    expect(validateReportMeta({ title: '血常规', examinationDate: '2026-10-01' }, today, draft)).toEqual({})
  })

  it('counts title length by code points', () => {
    const ok = '报'.repeat(TITLE_MAX_LENGTH)
    expect(validateReportMeta({ title: ok, examinationDate: '' }, today, draft).title).toBeUndefined()
    expect(validateReportMeta({ title: ok + '告', examinationDate: '' }, today, draft).title).toBeTruthy()
  })

  it('rejects future dates', () => {
    expect(validateReportMeta({ title: '', examinationDate: '2026-11-06' }, today, draft).examinationDate).toBe(
      '检查日期不能晚于今天',
    )
  })

  it('requires a date only for confirmed lab reports', () => {
    expect(validateReportMeta({ title: '', examinationDate: '' }, today, confirmedLab).examinationDate).toBeTruthy()
    expect(validateReportMeta({ title: '', examinationDate: '' }, today, confirmedImaging)).toEqual({})
    expect(validateReportMeta({ title: '', examinationDate: '' }, today, draft)).toEqual({})
  })
})

describe('toMetaPayload / metaUnchanged', () => {
  it('maps blank values to null', () => {
    expect(toMetaPayload({ title: '  ', examinationDate: '' })).toEqual({ title: null, examinationDate: null })
    expect(toMetaPayload({ title: ' 尿常规 ', examinationDate: '2026-01-02' })).toEqual({
      title: '尿常规',
      examinationDate: '2026-01-02',
    })
  })

  it('detects no-op edits', () => {
    const current = { title: '血常规', examinationDate: '2026-10-01' }
    expect(metaUnchanged({ title: ' 血常规 ', examinationDate: '2026-10-01' }, current)).toBe(true)
    expect(metaUnchanged({ title: '血常规', examinationDate: '2026-10-02' }, current)).toBe(false)
    expect(metaUnchanged({ title: '', examinationDate: '' }, { title: null, examinationDate: null })).toBe(true)
  })
})
