import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchArrearsLedger, fetchLineExpiryLedger } from './api'

describe('ledger date-range requests', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('passes an explicit arrears month range through to the API', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ data: {} }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await fetchArrearsLedger({ statisticsDateFrom: '202610', statisticsDateTo: '202612' })

    const requestUrl = fetchMock.mock.calls[0][0] as string
    const params = new URL(requestUrl, 'http://localhost').searchParams
    expect(params.get('statisticsDateFrom')).toBe('202610')
    expect(params.get('statisticsDateTo')).toBe('202612')
  })

  it('passes an explicit line-date range through to the API', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ data: {} }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await fetchLineExpiryLedger({ statisticsDateFrom: '20261001', statisticsDateTo: '20261031' })

    const requestUrl = fetchMock.mock.calls[0][0] as string
    const params = new URL(requestUrl, 'http://localhost').searchParams
    expect(params.get('statisticsDateFrom')).toBe('20261001')
    expect(params.get('statisticsDateTo')).toBe('20261031')
  })
})
