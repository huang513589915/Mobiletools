const BUSINESS_TIME_ZONE = 'Asia/Shanghai'

function businessYearMonth(date: Date) {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: BUSINESS_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(date)
  return {
    year: parts.find(part => part.type === 'year')?.value ?? '',
    month: parts.find(part => part.type === 'month')?.value ?? '',
  }
}

export function currentBusinessMonth(date: Date = new Date()) {
  const { year, month } = businessYearMonth(date)
  return year + month
}

export function currentBusinessMonthDateRange(date: Date = new Date()) {
  const { year, month } = businessYearMonth(date)
  const daysInMonth = new Date(Date.UTC(Number(year), Number(month), 0)).getUTCDate()
  return {
    statisticsDateFrom: year + month + '01',
    statisticsDateTo: year + month + String(daysInMonth).padStart(2, '0'),
  }
}
