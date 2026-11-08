// 当前绝对时间响应明确携带偏移；无偏移北京时间仅为旧版本/旧模拟数据的兼容。
export function formatBeijingTime(value: string, dateOnly = false): string {
  const hasOffset = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
  const date = new Date(hasOffset ? value : `${value}+08:00`)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'medium',
    ...(dateOnly ? {} : { timeStyle: 'short' as const, hourCycle: 'h23' as const }),
  }).format(date)
}
