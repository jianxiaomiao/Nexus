// Format instants as Beijing time independently of the phone's selected timezone.
export function beijingTime(value, dateOnly = false) {
	if (typeof value !== 'string') return '—'
	const time = Date.parse(
		/(?:Z|[+-]\d\d:\d\d)$/i.test(value) ? value : value + '+08:00',
	)
	if (!Number.isFinite(time)) return '—'
	const text = new Date(time + 8 * 3600000).toISOString().replace('T', ' ')
	return text.slice(0, dateOnly ? 10 : 16)
}
export function customRange(start, end) {
	const valid = (value) =>
		/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) &&
		Number.isFinite(Date.parse(value + 'Z')) &&
		new Date(Date.parse(value + 'Z')).toISOString().slice(0, 16) === value
	if (!valid(start) || !valid(end))
		return '请输入有效的北京时间，例如 2026-11-18T09:00'
	const duration = Date.parse(end + 'Z') - Date.parse(start + 'Z')
	if (duration <= 0) return '结束时间必须晚于开始时间'
	if (duration > 90 * 86400000) return '自定义范围最多90天'
	return ''
}
