import { getAuth, register, authMessage, goToLogin } from '../auth/runtime.js'
import { SessionError } from '../auth/session.js'

export function positiveId(value) {
	const id = Number(value)
	if (!Number.isSafeInteger(id) || id <= 0)
		throw new SessionError('INVALID_REQUEST')
	return id
}
const query = (values) =>
	Object.entries(values)
		.filter(([, v]) => v !== undefined)
		.map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v))
		.join('&')
export async function manage(path, options = {}) {
	try {
		return await (await getAuth()).management(path, options)
	} catch (error) {
		if (
			options.method &&
			options.method !== 'GET' &&
			([
				'NETWORK_UNAVAILABLE',
				'REQUEST_UNAVAILABLE',
				'INVALID_RESPONSE',
				'SESSION_CHANGED',
			].includes(error.code) ||
				error.status >= 500)
		)
			throw new SessionError('MUTATION_RESULT_UNKNOWN')
		throw error
	}
}
function page(data) {
	if (
		!Array.isArray(data?.records) ||
		!Number.isSafeInteger(data.total) ||
		data.total < 0
	)
		throw new SessionError('INVALID_RESPONSE')
	return data
}
export async function applications(current = 1, applicationId) {
	return page(
		await manage(
			'/api/application?' + query({ current, size: 10, applicationId }),
		),
	)
}
export async function application(id) {
	const data = await applications(1, positiveId(id))
	const found = data.records.find((row) => row.id === Number(id))
	if (!found) throw new SessionError('APPLICATION_NOT_FOUND')
	return found
}
export const createApplication = (name) =>
	manage('/api/application/create', {
		method: 'POST',
		body: { name: name.trim() },
	})
export const updateApplication = (id, changes) =>
	manage('/api/application', {
		method: 'PUT',
		body: { ...changes, id: positiveId(id) },
	})
export const deleteApplication = (id) =>
	manage('/api/application/' + positiveId(id), { method: 'DELETE' })
export const quota = (id) =>
	manage('/api/application/' + positiveId(id) + '/quota')
export async function keys(applicationId, current = 1, apiKeyId) {
	const result = page(
		await manage(
			'/api/apiKey/' +
				positiveId(applicationId) +
				'?' +
				query({ current, size: 10, apiKeyId }),
		),
	)
	// List state never retains a credential accidentally added to a response.
	return { ...result, records: result.records.map(keyFields) }
}
function keyFields(row) {
	return Object.fromEntries(
		[
			'id',
			'applicationId',
			'name',
			'publicId',
			'keyPreview',
			'status',
			'createdAt',
			'updatedAt',
		]
			.filter((field) => row[field] !== undefined)
			.map((field) => [field, row[field]]),
	)
}
function secretResponse(data) {
	if (typeof data?.apiKey !== 'string' || !/^\S{1,1024}$/.test(data.apiKey))
		throw new SessionError('MUTATION_RESULT_UNKNOWN')
	return { ...keyFields(data), apiKey: data.apiKey }
}
export async function key(applicationId, id) {
	const result = await keys(applicationId, 1, positiveId(id))
	const found = result.records.find((row) => row.id === Number(id))
	if (!found) throw new SessionError('API_KEY_NOT_FOUND')
	return found
}
export const createKey = async (applicationId, name) =>
	secretResponse(
		await manage('/api/apiKey/create', {
			method: 'POST',
			body: {
				applicationId: positiveId(applicationId),
				name: name.trim(),
			},
		}),
	)
export const updateKey = async (applicationId, apiKeyId, changes) =>
	keyFields(
		await manage('/api/apiKey', {
			method: 'PUT',
			body: {
				...changes,
				applicationId: positiveId(applicationId),
				apiKeyId: positiveId(apiKeyId),
			},
		}),
	)
export const deleteKey = (applicationId, apiKeyId) =>
	manage('/api/apiKey', {
		method: 'DELETE',
		body: {
			applicationId: positiveId(applicationId),
			apiKeyId: positiveId(apiKeyId),
		},
	})
export const rotateKey = async (id, expectedPublicId) =>
	secretResponse(
		await manage('/api/apiKey/' + positiveId(id) + '/rotate', {
			method: 'POST',
			body: { expectedPublicId },
		}),
	)
export const usage = (params) => manage('/api/usage?' + query(params))
export async function usageEvents(params) {
	return page(await manage('/api/usage/events?' + query(params)))
}
export async function registerAccount(body) {
	return register(body)
}
export async function requireLogin() {
	const auth = await getAuth()
	await auth.ensure()
	return auth
}
export async function showError(error) {
	const auth = await getAuth().catch(() => null)
	if (auth && !auth.state().authenticated) goToLogin()
	return message(error)
}
export function message(error) {
	return (
		{
			RESOURCE_CHANGED: '资料已被修改，请刷新核对后重新操作。',
			APPLICATION_NAME_ALREADY_EXISTS:
				'这个 Application 名称已被使用，请换一个名称。',
			APPLICATION_NOT_FOUND:
				'Application 不存在、已删除，或你没有访问权限。',
			APPLICATION_DISABLED: '所属 Application 已禁用，不能创建 API Key。',
			API_KEY_NAME_ALREADY_EXISTS: '这个 API Key 名称已被使用。',
			API_KEY_NOT_FOUND: 'API Key 不存在、已删除，或你没有访问权限。',
			API_KEY_ROTATION_CONFLICT:
				'Key 已发生变化，请刷新核对 Public ID 后再决定是否轮换。',
			AUTH_EMAIL_ALREADY_REGISTERED: '这个邮箱已注册，请直接登录。',
			VALIDATION_ERROR: '请检查填写内容的格式和长度。',
			INVALID_USAGE_TIME_RANGE: '请选择有效的北京时间范围，最多90天。',
			INVALID_LIST_PAGE: '页码无效，请回到第一页。',
			INVALID_REQUEST: '页面参数或输入无效，请返回后重试。',
			MUTATION_RESULT_UNKNOWN:
				'操作结果尚未确认。请先刷新核对后再决定下一步，不要直接重复提交。',
			REQUEST_UNAVAILABLE: '服务暂时不可用，请稍后重试。',
			INVALID_RESPONSE: '未收到有效数据，请刷新后重试。',
		}[error?.code] || authMessage(error)
	)
}
export const nameError = (value) =>
	!value.trim()
		? '请输入名称'
		: value.trim().length > 64
			? '名称不能超过64个字符'
			: ''
export const statusLabel = (value) =>
	value === 0 ? '已启用' : value === 1 ? '已禁用' : '状态未知'
export const statusTone = (value) =>
	value === 0 ? 'success' : value === 1 ? 'warning' : 'neutral'
