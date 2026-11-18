import { API_ORIGIN } from '../config/backend.js'
import { createSession, SessionError } from './session.js'
import { createAndroidVault } from './android-vault.js'
import { createTransport } from '../api/transport.js'

let authPromise
const request = createTransport({
	origin: API_ORIGIN,
	send: (options) => uni.request(options),
})
export async function register(body) {
	await getAuth()
	return request('/api/auth/register', { method: 'POST', body })
}

export function getAuth() {
	if (!authPromise) {
		authPromise = (async () => {
			// #ifdef APP-PLUS
			for (
				let attempt = 0;
				attempt < 40 && typeof plus === 'undefined';
				attempt++
			) {
				await new Promise((resolve) => setTimeout(resolve, 50))
			}
			if (typeof plus !== 'undefined' && plus.os.name === 'Android') {
				try {
					return createSession({
						request,
						vault: createAndroidVault(API_ORIGIN),
					})
				} catch {
					throw new SessionError('SECURE_STORAGE_UNAVAILABLE')
				}
			}
			// #endif
			throw new SessionError('UNSUPPORTED_PLATFORM')
		})().catch((error) => {
			authPromise = null
			throw error
		})
	}
	return authPromise
}

export function authMessage(error) {
	return (
		{
			AUTH_INVALID_CREDENTIALS: '邮箱或密码错误',
			AUTH_ACCOUNT_FORBIDDEN: '账号当前不可用',
			NETWORK_UNAVAILABLE: '无法连接测试服务，请检查 USB 连接后重试',
			SECURE_STORAGE_UNAVAILABLE:
				'无法访问安全登录存储，请重启 App 后重试',
			UNSUPPORTED_PLATFORM: '请在安卓 App 中登录，当前平台仅支持界面预览',
			SESSION_EXPIRED: '登录已到期，请重新登录',
			INVALID_REFRESH_TOKEN: '登录已失效，请重新登录',
			REFRESH_TOKEN_REPLAY: '登录已失效，请重新登录',
			REFRESH_TOKEN_REPLAYED: '登录已失效，请重新登录',
			SESSION_RESULT_UNKNOWN: '无法确认上次登录状态，请重新登录',
			LOGIN_REQUIRED: '请先登录',
			INVALID_RESPONSE: '无法确认服务响应，请重新登录',
			INVALID_ACCESS_TOKEN: '登录凭据已更新，请重试本次操作',
			SESSION_CHANGED: '登录状态已变化，请重试',
			LOGIN_PENDING: '正在登录，请稍候',
		}[error?.code] || '登录服务暂时不可用，请稍后重试'
	)
}

export function goToLogin() {
	uni.reLaunch({ url: '/pages/login/login' })
}
export function goToApplications() {
	uni.reLaunch({ url: '/pages/applications/applications' })
}
