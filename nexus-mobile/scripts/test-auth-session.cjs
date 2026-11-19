const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const source = fs.readFileSync(
	path.join(__dirname, '../auth/session.js'),
	'utf8',
)
const modulePromise = import(
	'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
)
test('management allowlist binds each endpoint to its HTTP method', async () => {
	const { managementAllowed } = await modulePromise
	for (const [path, method] of [
		['/api/application?current=1', 'GET'],
		['/api/application/create', 'POST'],
		['/api/application', 'PUT'],
		['/api/application/1', 'DELETE'],
		['/api/application/1/quota', 'GET'],
		['/api/apiKey/1?apiKeyId=2', 'GET'],
		['/api/apiKey/create', 'POST'],
		['/api/apiKey', 'PUT'],
		['/api/apiKey', 'DELETE'],
		['/api/apiKey/2/rotate', 'POST'],
		['/api/usage?apiKeyId=2', 'GET'],
		['/api/usage/events?current=1', 'GET'],
	])
		assert.equal(managementAllowed(path, method), true, method + ' ' + path)
	for (const [path, method] of [
		['/api/auth/login', 'POST'],
		['/v1/utils/uuid', 'GET'],
		['https://elsewhere.test/api/application', 'GET'],
		['/api/application/create', 'GET'],
		['/api/usage', 'POST'],
		['/api/application/0', 'DELETE'],
		['/api/application/1/../auth/logout', 'POST'],
		['/api/application#x', 'GET'],
		['/api/application', 'PATCH'],
	])
		assert.equal(
			managementAllowed(path, method),
			false,
			method + ' ' + path,
		)
})
const deferred = () => {
	let resolve, reject
	const promise = new Promise((a, b) => {
		resolve = a
		reject = b
	})
	return { promise, resolve, reject }
}
async function fixture(initial = null) {
	const { createSession, SessionError } = await modulePromise
	let time = Date.parse('2026-11-17T00:00:00Z'),
		record = initial,
		calls = [],
		failWrites = false
	const queue = []
	const vault = {
		read: () => structuredClone(record),
		write: (value) => {
			if (failWrites) throw Error('disk')
			record = structuredClone(value)
		},
	}
	const request = async (p, o) => {
		calls.push({ path: p, ...o })
		if (!queue.length) throw Error('Unexpected request')
		const next = queue.shift()
		return typeof next === 'function' ? next() : next
	}
	const auth = createSession({ request, vault, now: () => time })
	const tokens = (n = 1, extra = {}) => ({
		accessToken: 'access-' + n,
		refreshToken: String.fromCharCode(64 + n).repeat(43),
		tokenType: 'Bearer',
		expiresInSeconds: 8,
		sessionId: '123',
		sessionExpiresAt: '2026-11-18T00:00:00Z',
		...extra,
	})
	return {
		auth,
		SessionError,
		tokens,
		calls,
		queue,
		vault,
		get record() {
			return record
		},
		set time(v) {
			time = v
		},
		get time() {
			return time
		},
		set failWrites(v) {
			failWrites = v
		},
		restart: () => createSession({ request, vault, now: () => time }),
	}
}
for (const [path, method] of [
	['/api/application/create', 'POST'],
	['/api/apiKey', 'PUT'],
	['/api/apiKey', 'DELETE'],
	['/api/apiKey/2/rotate', 'POST'],
]) {
	test(
		method + ' ' + path + ' is not replayed after a current 401 renewal',
		async () => {
			const f = await fixture()
			f.queue.push(f.tokens())
			await f.auth.login('a', 'p')
			f.queue.push(() => {
				throw new f.SessionError('INVALID_ACCESS_TOKEN')
			}, f.tokens(2))
			await assert.rejects(
				f.auth.management(path, { method, body: { name: 'test' } }),
				{ code: 'INVALID_ACCESS_TOKEN' },
			)
			assert.equal(f.calls.filter((call) => call.path === path).length, 1)
			assert.equal(
				f.calls.filter((call) => call.path === '/api/auth/refresh')
					.length,
				1,
			)
			assert.equal(f.auth.state().authenticated, true)
		},
	)
}
test('login persists RT and metadata, never password or AT', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login(' a@example.test ', 'SecretValue')
	assert.equal(f.calls[0].body.email, 'a@example.test')
	assert.equal(f.record.phase, 'ready')
	assert.ok(f.record.refreshToken)
	assert.equal(f.record.accessToken, undefined)
	assert.equal(f.record.password, undefined)
	assert.equal(f.auth.state().accessToken, undefined)
})
test('fresh process restores by rotating saved RT', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.queue.push(f.tokens(2))
	const next = f.restart()
	await next.initialize()
	assert.equal(next.state().authenticated, true)
	assert.equal(f.calls.at(-1).path, '/api/auth/refresh')
	assert.equal(f.record.refreshToken, f.tokens(2).refreshToken)
})
test('expired AT requests merge one refresh', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	const d = deferred()
	f.queue.push(() => d.promise)
	const a = f.auth.ensure(),
		b = f.auth.ensure()
	await new Promise(setImmediate)
	assert.equal(f.calls.filter((c) => c.path.endsWith('/refresh')).length, 1)
	d.resolve(f.tokens(2))
	await Promise.all([a, b])
	assert.equal(f.record.phase, 'ready')
})
test('fresh AT needs no refresh', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	await f.auth.ensure()
	assert.equal(f.calls.length, 1)
})
test('absolute expiry cannot be extended or refreshed', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 86400000
	await assert.rejects(f.auth.ensure(), { code: 'SESSION_EXPIRED' })
	assert.equal(f.calls.length, 1)
	assert.equal(f.record.phase, 'logged-out')
})
test('working crash marker never replays previous RT', async () => {
	const f = await fixture({ version: 1, phase: 'working' })
	await f.auth.initialize()
	assert.equal(f.calls.length, 0)
	assert.equal(f.auth.state().authenticated, false)
	assert.equal(f.record.phase, 'logged-out')
})
test('logged-out marker never restores', async () => {
	const f = await fixture({ version: 1, phase: 'logged-out' })
	await f.auth.initialize()
	assert.equal(f.calls.length, 0)
})
test('refresh commits working before sending any RT', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	f.queue.push(() => {
		assert.equal(f.record.phase, 'working')
		assert.equal(f.record.refreshToken, undefined)
		return f.tokens(2)
	})
	await f.auth.ensure()
})
test('lost refresh response ends session without retry', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	f.queue.push(() => {
		throw new f.SessionError('NETWORK_UNAVAILABLE')
	})
	await assert.rejects(f.auth.ensure())
	await f.restart().initialize()
	assert.equal(f.calls.length, 2)
	assert.equal(f.record.phase, 'logged-out')
})
test('logout clears memory and durable RT before network finishes', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	const d = deferred()
	f.queue.push(() => d.promise)
	const pending = f.auth.logout()
	assert.equal(f.auth.state().authenticated, false)
	assert.equal(f.record.phase, 'logged-out')
	assert.equal(f.record.refreshToken, undefined)
	d.resolve(null)
	await pending
	assert.equal(f.auth.state().revocationUnconfirmed, false)
})
test('unconfirmed revocation survives restart and failed new login', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.queue.push(() => {
		throw new Error('offline')
	})
	await f.auth.logout()
	const next = f.restart()
	await next.initialize()
	assert.equal(next.state().revocationUnconfirmed, true)
	f.queue.push(() => {
		throw new f.SessionError('AUTH_INVALID_CREDENTIALS')
	})
	await assert.rejects(next.login('a', 'bad'))
	assert.equal(next.state().revocationUnconfirmed, true)
	assert.equal(f.record.revocationUnconfirmed, true)
})
test('logout during refresh blocks stale restoration and revokes late token', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	const d = deferred()
	f.queue.push(() => d.promise)
	const pending = f.auth.ensure()
	await new Promise(setImmediate)
	f.queue.push(null)
	await f.auth.logout()
	f.queue.push(null)
	d.resolve(f.tokens(2))
	await assert.rejects(pending, { code: 'SESSION_CHANGED' })
	assert.equal(f.auth.state().authenticated, false)
	assert.equal(f.record.phase, 'logged-out')
	assert.equal(f.calls.at(-1).accessToken, 'access-2')
})
test('logout during login revokes late created session', async () => {
	const f = await fixture()
	const d = deferred()
	f.queue.push(() => d.promise)
	const pending = f.auth.login('a', 'p')
	await f.auth.logout()
	f.queue.push(null)
	d.resolve(f.tokens())
	await assert.rejects(pending, { code: 'SESSION_CHANGED' })
	assert.equal(f.record.phase, 'logged-out')
})
test('current 401 renews but never replays business request', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.queue.push(() => {
		throw new f.SessionError('INVALID_ACCESS_TOKEN')
	}, f.tokens(2))
	await assert.rejects(f.auth.management('/api/application'), {
		code: 'INVALID_ACCESS_TOKEN',
	})
	assert.equal(f.calls.filter((c) => c.path === '/api/application').length, 1)
	assert.equal(f.auth.state().authenticated, true)
})
test('stale 401 cannot invalidate a newer login', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	const d = deferred()
	f.queue.push(() => d.promise)
	const old = f.auth.management('/api/application')
	await new Promise(setImmediate)
	f.queue.push(f.tokens(2, { sessionId: '456' }))
	await f.auth.login('b', 'p')
	d.reject(new f.SessionError('INVALID_ACCESS_TOKEN'))
	await assert.rejects(old)
	assert.equal(f.record.sessionId, '456')
	assert.equal(f.calls.filter((c) => c.path.endsWith('/refresh')).length, 0)
})
for (const [name, change] of [
	['session ID', { sessionId: '456' }],
	['absolute deadline', { sessionExpiresAt: '2026-11-19T00:00:00Z' }],
	['same RT', { refreshToken: 'A'.repeat(43) }],
])
	test('rotation rejects changed ' + name, async () => {
		const f = await fixture()
		f.queue.push(f.tokens())
		await f.auth.login('a', 'p')
		f.time += 9000
		f.queue.push(f.tokens(2, change))
		await assert.rejects(f.auth.ensure(), { code: 'INVALID_RESPONSE' })
		assert.equal(f.auth.state().authenticated, false)
	})
for (const [name, change] of [
	['missing token', { accessToken: '' }],
	['bad RT', { refreshToken: 'bad' }],
	['numeric sid', { sessionId: 123 }],
	['bad TTL', { expiresInSeconds: 0 }],
	['expired deadline', { sessionExpiresAt: '2026-11-16T00:00:00Z' }],
])
	test('login rejects ' + name, async () => {
		const f = await fixture()
		f.queue.push(f.tokens(1, change))
		await assert.rejects(f.auth.login('a', 'p'), {
			code: 'INVALID_RESPONSE',
		})
		assert.equal(f.record.phase, 'logged-out')
	})
test('storage failure before login blocks credential request', async () => {
	const f = await fixture()
	f.failWrites = true
	await assert.rejects(f.auth.login('a', 'p'), {
		code: 'SECURE_STORAGE_UNAVAILABLE',
	})
	assert.equal(f.calls.length, 0)
})
test('storage failure before refresh blocks RT request', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	f.failWrites = true
	await assert.rejects(f.auth.ensure(), {
		code: 'SECURE_STORAGE_UNAVAILABLE',
	})
	assert.equal(f.calls.length, 1)
})
test('failed persistence of rotated result revokes returned AT', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	f.queue.push(() => {
		f.failWrites = true
		return f.tokens(2)
	}, null)
	await assert.rejects(f.auth.ensure(), {
		code: 'SECURE_STORAGE_UNAVAILABLE',
	})
	assert.equal(f.calls.at(-1).path, '/api/auth/logout')
	assert.equal(f.calls.at(-1).accessToken, 'access-2')
	assert.equal(f.record.phase, 'working')
})
test('management rejects absolute and non-management URLs', async () => {
	const f = await fixture()
	for (const p of [
		'https://evil.test',
		'/v1/utils/uuid',
		'/api/auth/refresh',
	])
		await assert.rejects(f.auth.management(p), { code: 'INVALID_REQUEST' })
	assert.equal(f.calls.length, 0)
})
test('a stale renewal does not block a newer authenticated session', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.time += 9000
	const d = deferred()
	f.queue.push(() => d.promise)
	const old = f.auth.ensure()
	await new Promise(setImmediate)
	f.queue.push(f.tokens(2, { sessionId: '456' }))
	await f.auth.login('b', 'p')
	await f.auth.ensure()
	f.queue.push(null)
	d.resolve(f.tokens(3))
	await assert.rejects(old, { code: 'SESSION_CHANGED' })
	assert.equal(f.record.sessionId, '456')
})
test('logout storage failure still attempts server revocation', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.failWrites = true
	f.queue.push(null)
	await assert.rejects(f.auth.logout(), {
		code: 'SECURE_STORAGE_UNAVAILABLE',
	})
	assert.equal(f.calls.at(-1).path, '/api/auth/logout')
	assert.equal(f.auth.state().authenticated, false)
})
test('failed logout retains proof for a retry when storage recovers', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.failWrites = true
	f.queue.push(() => {
		throw Error('offline')
	})
	await assert.rejects(f.auth.logout())
	f.failWrites = false
	f.queue.push(null)
	await f.auth.logout()
	assert.equal(f.calls.at(-1).accessToken, 'access-1')
	assert.equal(f.record.phase, 'logged-out')
})
test('blank credentials do not change durable session state or send requests', async () => {
	const f = await fixture()
	for (const [email, password] of [
		[' ', 'p'],
		['a', ' '],
		['a', 'x'.repeat(65)],
	])
		await assert.rejects(f.auth.login(email, password), {
			code: 'INVALID_REQUEST',
		})
	assert.equal(f.calls.length, 0)
	assert.equal(f.record, null)
})
test('current forbidden account clears local credentials without renewal', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.queue.push(() => {
		throw new f.SessionError('AUTH_ACCOUNT_FORBIDDEN')
	})
	await assert.rejects(f.auth.management('/api/application'), {
		code: 'AUTH_ACCOUNT_FORBIDDEN',
	})
	assert.equal(f.auth.state().authenticated, false)
	assert.equal(f.record.phase, 'logged-out')
	assert.equal(f.calls.filter((c) => c.path.endsWith('/refresh')).length, 0)
})
test('malformed successful logout response remains unconfirmed', async () => {
	const f = await fixture()
	f.queue.push(f.tokens())
	await f.auth.login('a', 'p')
	f.queue.push({ unexpected: true })
	await f.auth.logout()
	assert.equal(f.auth.state().authenticated, false)
	assert.equal(f.record.revocationUnconfirmed, true)
})

test('a late management success is rejected after another request ends the session', async () => {
  const f = await fixture()
  f.queue.push(f.tokens())
  await f.auth.login('test@example.test', 'fixture-only')
  const slow = deferred()
  f.queue.push(() => slow.promise, () => { throw new f.SessionError('INVALID_ACCESS_TOKEN', 401) }, () => { throw new f.SessionError('NETWORK_UNAVAILABLE') })
  const old = f.auth.management('/api/application')
  const rejectedOld = assert.rejects(old, { code: 'SESSION_CHANGED' })
  await assert.rejects(f.auth.management('/api/usage'), { code: 'NETWORK_UNAVAILABLE' })
  assert.equal(f.auth.state().authenticated, false)
  slow.resolve({ records: [{ id: 1 }], total: 1 })
  await rejectedOld
})
