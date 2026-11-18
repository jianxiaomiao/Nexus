export class SessionError extends Error {
	constructor(code, status = 0) {
		super(code)
		this.name = 'SessionError'
		this.code = code
		this.status = status
	}
}

// Platform-independent rules. The vault is synchronous and commits before a request.
export function createSession({ request, vault, now = Date.now }) {
	let session = null,
		epoch = 0,
		initialized = false,
		bootstrap,
		renewal,
		loginTask
	let notice = '',
		revocationUnconfirmed = false,
		logoutProof = null,
		logoutPriorWarning = false
	const fail = (code) => {
		throw new SessionError(code)
	}
	const state = () => ({
		authenticated: !!session,
		notice,
		revocationUnconfirmed,
		sessionExpiresAt: session?.deadline ?? null,
	})
	const write = (record) => {
		try {
			vault.write({ version: 1, revocationUnconfirmed, ...record })
		} catch {
			fail('SECURE_STORAGE_UNAVAILABLE')
		}
	}
	function end(code) {
		session = null
		notice = code || ''
		write({ phase: 'logged-out' })
	}
	function decode(data) {
		const deadline =
			typeof data?.sessionExpiresAt === 'string'
				? Date.parse(data.sessionExpiresAt)
				: NaN
		if (
			!data ||
			data.tokenType !== 'Bearer' ||
			typeof data.accessToken !== 'string' ||
			!/^\S{1,4096}$/.test(data.accessToken) ||
			typeof data.refreshToken !== 'string' ||
			!/^[A-Za-z0-9_-]{43}$/.test(data.refreshToken) ||
			typeof data.sessionId !== 'string' ||
			!/^[1-9][0-9]{0,18}$/.test(data.sessionId) ||
			!Number.isSafeInteger(data.expiresInSeconds) ||
			data.expiresInSeconds <= 0 ||
			!Number.isFinite(deadline) ||
			deadline <= now()
		)
			fail('INVALID_RESPONSE')
		return {
			accessToken: data.accessToken,
			refreshToken: data.refreshToken,
			sessionId: data.sessionId,
			expiresAt: Math.min(now() + data.expiresInSeconds * 1000, deadline),
			deadline,
		}
	}
	function persist(next) {
		write({
			phase: 'ready',
			refreshToken: next.refreshToken,
			sessionId: next.sessionId,
			deadline: next.deadline,
		})
		session = next
		notice = ''
	}
	async function revoke(accessToken) {
		try {
			return (
				(await request('/api/auth/logout', {
					method: 'POST',
					accessToken,
				})) === null
			)
		} catch {
			return false
		}
	}
	async function discardResponse(data, version) {
		let token
		try {
			token = decode(data).accessToken
		} catch {
			return
		}
		const confirmed = await revoke(token)
		if (
			version === epoch &&
			!session &&
			confirmed &&
			revocationUnconfirmed
		) {
			revocationUnconfirmed = false
			write({ phase: 'logged-out' })
		}
	}
	function renew(original) {
		if (renewal?.version === epoch) return renewal.promise
		const version = epoch
		const task = { version, promise: null }
		task.promise = (async () => {
			if (original.deadline <= now()) {
				end('SESSION_EXPIRED')
				fail('SESSION_EXPIRED')
			}
			// Crash/timeout after this commit cannot restore and replay the old RT.
			write({ phase: 'working' })
			try {
				const data = await request('/api/auth/refresh', {
					method: 'POST',
					body: { refreshToken: original.refreshToken },
				})
				if (version !== epoch) {
					await discardResponse(data, epoch)
					fail('SESSION_CHANGED')
				}
				const next = decode(data)
				if (
					next.sessionId !== original.sessionId ||
					next.deadline !== original.deadline ||
					next.refreshToken === original.refreshToken
				)
					fail('INVALID_RESPONSE')
				try {
					persist(next)
				} catch (error) {
					await revoke(next.accessToken)
					throw error
				}
				return state()
			} catch (error) {
				if (version === epoch)
					end(error.code || 'SESSION_RESULT_UNKNOWN')
				throw error
			}
		})().finally(() => {
			if (renewal === task) renewal = null
		})
		renewal = task
		return task.promise
	}
	async function initialize() {
		if (bootstrap) return bootstrap
		if (initialized) return state()
		bootstrap = (async () => {
			let record
			try {
				record = vault.read()
			} catch {
				notice = 'SECURE_STORAGE_UNAVAILABLE'
				fail(notice)
			}
			initialized = true
			if (!record) return state()
			if (record.version !== 1) {
				end('SAVED_SESSION_INVALID')
				return state()
			}
			revocationUnconfirmed = record.revocationUnconfirmed === true
			if (record.phase === 'logged-out') return state()
			if (record.phase !== 'ready') {
				end('SESSION_RESULT_UNKNOWN')
				return state()
			}
			if (
				typeof record.refreshToken !== 'string' ||
				!/^[A-Za-z0-9_-]{43}$/.test(record.refreshToken) ||
				typeof record.sessionId !== 'string' ||
				!/^[1-9][0-9]{0,18}$/.test(record.sessionId) ||
				!Number.isFinite(record.deadline)
			) {
				end('SAVED_SESSION_INVALID')
				return state()
			}
			await renew(record)
			return state()
		})().finally(() => {
			bootstrap = null
		})
		return bootstrap
	}
	async function login(email, password) {
		if (
			typeof email !== 'string' ||
			!email.trim() ||
			email.trim().length > 254 ||
			typeof password !== 'string' ||
			!password.trim() ||
			password.length > 64
		)
			fail('INVALID_REQUEST')
		if (loginTask) fail('LOGIN_PENDING')
		const version = ++epoch
		initialized = true
		session = null
		notice = ''
		write({ phase: 'working' })
		loginTask = (async () => {
			try {
				const data = await request('/api/auth/login', {
					method: 'POST',
					body: { email: email.trim(), password },
				})
				if (version !== epoch) {
					await discardResponse(data, epoch)
					fail('SESSION_CHANGED')
				}
				const next = decode(data)
				try {
					persist(next)
				} catch (error) {
					await revoke(next.accessToken)
					throw error
				}
				return state()
			} catch (error) {
				if (version === epoch) end(error.code || 'AUTH_UNAVAILABLE')
				throw error
			}
		})().finally(() => {
			loginTask = null
		})
		return loginTask
	}
	async function ensure() {
		await initialize()
		if (loginTask) fail('LOGIN_PENDING')
		if (!session) fail('LOGIN_REQUIRED')
		if (session.deadline <= now()) {
			++epoch
			end('SESSION_EXPIRED')
			fail('SESSION_EXPIRED')
		}
		if (renewal?.version === epoch) return renewal.promise
		if (session.expiresAt <= now()) return renew(session)
		return state()
	}
	async function management(path, options = {}) {
		if (!managementAllowed(path, options.method || 'GET'))
			fail('INVALID_REQUEST')
		await ensure()
		const original = session,
			version = epoch
		try {
			const data = await request(path, {
				...options,
				accessToken: original.accessToken,
			})
			if (version !== epoch) fail('SESSION_CHANGED')
			return data
		} catch (error) {
			if (
				error.code === 'INVALID_ACCESS_TOKEN' &&
				version === epoch &&
				session === original
			) {
				await renew(original)
			}
			if (
				error.code === 'AUTH_ACCOUNT_FORBIDDEN' &&
				version === epoch &&
				session === original
			) {
				++epoch
				end('AUTH_ACCOUNT_FORBIDDEN')
			}
			// The original business request is never replayed, even after renewal.
			throw error
		}
	}
	async function logout() {
		const original = session || logoutProof,
			pending = !!loginTask || renewal?.version === epoch
		const retrying = !session && !!logoutProof
		const previousWarning = retrying
			? logoutPriorWarning
			: revocationUnconfirmed
		logoutPriorWarning = previousWarning
		const version = ++epoch
		session = null
		logoutProof = original
		initialized = true
		notice = ''
		revocationUnconfirmed = previousWarning || !!original || pending
		let storageError
		try {
			write({ phase: 'logged-out' })
		} catch (error) {
			storageError = error
		}
		if (original) {
			const confirmed = await revoke(original.accessToken)
			if (epoch === version && !session) {
				revocationUnconfirmed = previousWarning || !confirmed
				if (confirmed) logoutProof = null
				try {
					write({ phase: 'logged-out' })
					storageError = null
				} catch (error) {
					storageError = error
				}
			}
		}
		if (storageError) throw storageError
		return state()
	}
	return { state, initialize, login, ensure, management, logout }
}

// Bearer credentials may only reach the management endpoints used by this client.
export function managementAllowed(path, method) {
	if (typeof path !== 'string' || /[#\\\s]/.test(path)) return false
	const base = path.split('?')[0]
	if (method === 'GET')
		return /^\/api\/(?:application|application\/[1-9]\d*\/quota|apiKey\/[1-9]\d*|usage(?:\/events)?)$/.test(
			base,
		)
	if (path.includes('?')) return false
	if (method === 'POST')
		return /^\/api\/(?:application\/create|apiKey\/create|apiKey\/[1-9]\d*\/rotate)$/.test(
			base,
		)
	if (method === 'PUT') return /^\/api\/(?:application|apiKey)$/.test(base)
	if (method === 'DELETE')
		return /^\/api\/(?:application\/[1-9]\d*|apiKey)$/.test(base)
	return false
}
