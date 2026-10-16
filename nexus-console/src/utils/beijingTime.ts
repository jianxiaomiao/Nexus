// Application 的 DATETIME 响应没有偏移量，接口约定它表示北京时间。
export function formatBeijingTime(value: string, dateOnly = false): string {
  const hasOffset = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
  const date = new Date(hasOffset ? value : `${value}+08:00`)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'medium',
    ...(dateOnly ? {} : { timeStyle: 'short' as const, hourCycle: 'h23' as const }),
  }).format(date)
}
