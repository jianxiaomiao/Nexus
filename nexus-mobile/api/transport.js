import { SessionError } from '../auth/session.js'

const knownCodes = new Set([
	'RESOURCE_CHANGED',
	'AUTH_INVALID_CREDENTIALS',
	'AUTH_EMAIL_ALREADY_REGISTERED',
	'AUTH_ACCOUNT_FORBIDDEN',
	'INVALID_ACCESS_TOKEN',
	'INVALID_REFRESH_TOKEN',
	'REFRESH_TOKEN_REPLAYED',
	'AUTH_SESSION_UNAVAILABLE',
	'INVALID_REQUEST',
	'INVALID_REQUEST_BODY',
	'VALIDATION_ERROR',
	'APPLICATION_NAME_ALREADY_EXISTS',
	'APPLICATION_NOT_FOUND',
	'APPLICATION_DISABLED',
	'API_KEY_NAME_ALREADY_EXISTS',
	'API_KEY_NOT_FOUND',
	'API_KEY_ROTATION_CONFLICT',
	'INVALID_API_KEY_ROTATION',
	'INVALID_API_KEY_UPDATE',
	'INVALID_API_KEY_DELETE',
	'INVALID_APPLICATION_UPDATE',
	'INVALID_APPLICATION_ID',
	'INVALID_LIST_PAGE',
	'INVALID_USAGE_TIME_RANGE',
	'INVALID_USAGE_PAGINATION',
])

export function createTransport({ origin, send }) {
	return function request(path, { method = 'GET', body, accessToken } = {}) {
		if (
			(!/^https:\/\/[^/]+$/.test(origin) &&
				origin !== 'http://127.0.0.1:18086') ||
			!/^\/api\//.test(path) ||
			/[#\\\s]/.test(path)
		) {
			return Promise.reject(new SessionError('INVALID_BACKEND_ORIGIN'))
		}
		return new Promise((resolve, reject) => {
			send({
				url: origin + path,
				method,
				data: body,
				timeout: 10000,
				header: {
					Accept: 'application/json',
					'Content-Type': 'application/json',
					...(accessToken
						? { Authorization: 'Bearer ' + accessToken }
						: {}),
				},
				success(response) {
					const expectedStatus =
						path === '/api/auth/register' ? 201 : 200
					if (
						response.statusCode === expectedStatus &&
						response.data?.code === 'SUCCESS' &&
						Object.prototype.hasOwnProperty.call(
							response.data,
							'data',
						)
					)
						resolve(response.data.data)
					else
						reject(
							new SessionError(
								knownCodes.has(response.data?.code)
									? response.data.code
									: 'REQUEST_UNAVAILABLE',
								response.statusCode,
							),
						)
				},
				fail() {
					reject(new SessionError('NETWORK_UNAVAILABLE'))
				},
			})
		})
	}
}
