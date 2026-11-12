import { describe, expect, it } from 'vitest'
import { currentBusinessMonth, currentBusinessMonthDateRange } from './businessCalendar'

describe('business calendar defaults', () => {
  it('uses the Asia/Shanghai month when UTC has not crossed into it yet', () => {
    const justBeforeLocalNovember = new Date('2026-10-31T15:59:59.000Z')

    expect(currentBusinessMonth(justBeforeLocalNovember)).toBe('202610')
    expect(currentBusinessMonthDateRange(justBeforeLocalNovember)).toEqual({
      statisticsDateFrom: '20261001',
      statisticsDateTo: '20261031',
    })
  })

  it('moves to the next month exactly at the local month boundary', () => {
    const localNovemberFirst = new Date('2026-10-31T16:00:00.000Z')

    expect(currentBusinessMonth(localNovemberFirst)).toBe('202611')
    expect(currentBusinessMonthDateRange(localNovemberFirst)).toEqual({
      statisticsDateFrom: '20261101',
      statisticsDateTo: '20261130',
    })
  })

  it('uses the leap day for February month-end', () => {
    expect(currentBusinessMonthDateRange(new Date('2024-02-14T04:00:00.000Z'))).toEqual({
      statisticsDateFrom: '20240201',
      statisticsDateTo: '20240229',
    })
  })
})
