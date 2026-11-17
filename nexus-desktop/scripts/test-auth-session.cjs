const assert = require('node:assert/strict')
const { test } = require('node:test')
const { createAuthSession, isAuthPath } = require('../auth-session.cjs')

const NOW = Date.parse('2026-11-17T00:00:00Z')
const input = { expectedRevision: 0, email: 'fixture@example.test', password: 'fixture-only' }
const tokens = (extra = {}) => ({ accessToken: 'fixture-at', tokenType: 'Bearer', expiresInSeconds: 300,
  refreshToken: 'A'.repeat(43), sessionId: '7', sessionExpiresAt: new Date(NOW + 86_400_000).toISOString(), ...extra })
const response = data => Response.json({ code: 'SUCCESS', data })
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function noSecret(value) {
  const text = JSON.stringify(value)
  assert.equal(text.includes('refreshToken'), false)
  assert.equal(text.includes('A'.repeat(43)), false)
  assert.equal(text.includes(input.password), false)
}

test('Main commits the complete response and returns only a detached public snapshot', async () => {
  let calls = 0
  const session = createAuthSession({ now: () => NOW, fetch: async (url, options) => {
    calls++
    assert.equal(url, 'http://localhost:8080/api/auth/login')
    assert.equal(options.method, 'POST')
    assert.equal(options.redirect, 'error')
    assert.deepEqual(JSON.parse(options.body), { email: input.email, password: input.password })
    return response({ ...tokens(), debug: { refreshToken: 'A'.repeat(43) } })
  } })
  const result = await session.login(input)
  assert.equal(result.status, 'ok')
  assert.equal(result.authState.revision, 1)
  assert.deepEqual(result.authState.session, { accessToken: 'fixture-at', tokenType: 'Bearer',
    expiresAt: NOW + 300_000, sessionId: '7', sessionExpiresAt: NOW + 86_400_000 })
  noSecret(result)
  result.authState.session.accessToken = 'mutated-copy'
  assert.equal(session.read().authState.session.accessToken, 'fixture-at')
  noSecret(session.read())
  assert.equal(calls, 1)
})

test('AT expiry preserves the private session; reads do not renew or extend either deadline', async () => {
  let now = NOW, calls = 0
  const session = createAuthSession({ now: () => now, fetch: async () => { calls++; return response(tokens()) } })
  await session.login(input)
  now += 300_000
  const read = session.read()
  assert.equal(read.authState.revision, 1)
  assert.equal(read.authState.session.expiresAt, NOW + 300_000)
  assert.equal(read.authState.session.sessionExpiresAt, NOW + 86_400_000)
  noSecret(read)
  assert.equal(calls, 1)
  now = NOW + 86_400_000
  assert.deepEqual(session.read().authState, { revision: 2, session: null })
  assert.equal(session.read().authState.revision, 2)
})

test('Main clips the AT deadline to the fixed session deadline', async () => {
  const session = createAuthSession({ now: () => NOW, fetch: async () => response(tokens({
    sessionExpiresAt: new Date(NOW + 200_000).toISOString(),
  })) })
  assert.equal((await session.login(input)).authState.session.expiresAt, NOW + 200_000)
})

test('late login cannot undo a clear performed while awaiting the backend', async () => {
  const pending = deferred()
  const session = createAuthSession({ now: () => NOW, fetch: () => pending.promise })
  const login = session.login(input)
  assert.equal(session.clear({ expectedRevision: 0 }).status, 'ok')
  pending.resolve(response(tokens()))
  assert.equal((await login).code, 'SESSION_CHANGED')
  assert.deepEqual(session.read().authState, { revision: 1, session: null })
})

test('newest Main login intent wins even before a previous request has committed', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  const session = createAuthSession({ now: () => NOW, fetch: () => ++calls === 1 ? first.promise : second.promise })
  const old = session.login(input), current = session.login(input)
  first.resolve(response(tokens({ accessToken: 'old-at' })))
  assert.equal((await old).code, 'SESSION_CHANGED')
  second.resolve(response(tokens({ accessToken: 'new-at', sessionId: '8' })))
  assert.equal((await current).status, 'ok')
  assert.equal(session.read().authState.session.accessToken, 'new-at')
  assert.equal(session.read().authState.revision, 1)
})

test('old failure cannot overwrite or clear a newer successful session', async () => {
  const pending = deferred()
  let calls = 0
  const session = createAuthSession({ now: () => NOW, fetch: () => ++calls === 1 ? pending.promise : Promise.resolve(response(tokens())) })
  const old = session.login(input)
  await session.login(input)
  pending.resolve(Response.json({ refreshToken: 'A'.repeat(43) }, { status: 401 }))
  assert.equal((await old).code, 'SESSION_CHANGED')
  assert.equal(session.read().authState.revision, 1)
})

for (const [status, code] of [[400, 'INVALID_REQUEST'], [401, 'AUTH_INVALID_CREDENTIALS'],
  [403, 'AUTH_ACCOUNT_FORBIDDEN'], [503, 'AUTH_SESSION_UNAVAILABLE'], [201, 'AUTH_SESSION_UNAVAILABLE']]) {
  test(`HTTP ${status} is a safe failure and never changes an existing session`, async () => {
    let calls = 0
    const session = createAuthSession({ now: () => NOW, fetch: async () => ++calls === 1 ? response(tokens()) :
      Response.json({ code: 'UNTRUSTED', message: input.password, refreshToken: 'A'.repeat(43) }, { status }) })
    await session.login(input)
    const before = session.read()
    const result = await session.login({ ...input, expectedRevision: 1 })
    assert.equal(result.code, code)
    noSecret(result)
    assert.deepEqual(session.read(), before)
  })
}

for (const bad of [null, {}, tokens({ refreshToken: '' }), tokens({ refreshToken: 'B'.repeat(43) }),
  tokens({ expiresInSeconds: 0 }), tokens({ expiresInSeconds: 0.5 }), tokens({ sessionId: '0' }),
  tokens({ sessionExpiresAt: new Date(NOW).toISOString() }), tokens({ tokenType: 'ApiKey' })]) {
  test('malformed success does not commit credentials or expose its original body', async () => {
    const session = createAuthSession({ now: () => NOW, fetch: async () => response(bad) })
    const result = await session.login(input)
    assert.equal(result.code, 'INVALID_RESPONSE')
    noSecret(result)
    assert.deepEqual(session.read().authState, { revision: 0, session: null })
  })
}

test('oversized and non-JSON responses fail before any state change', async () => {
  for (const res of [new Response('not-json'), response(tokens({ padding: 'x'.repeat(1000) }))]) {
    const session = createAuthSession({ now: () => NOW, responseLimit: 800, fetch: async () => res })
    assert.equal((await session.login(input)).code, 'INVALID_RESPONSE')
    assert.equal(session.read().authState.session, null)
  }
})

test('timeout remains bounded even if fetch ignores abort; late completion cannot commit', async () => {
  const pending = deferred()
  const session = createAuthSession({ now: () => NOW, timeoutMs: 10, fetch: () => pending.promise })
  assert.equal((await session.login(input)).code, 'TIMEOUT')
  pending.resolve(response(tokens()))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(session.read().authState.session, null)
})

test('timeout during body streaming cannot commit a partial response', async () => {
  const session = createAuthSession({ now: () => NOW, timeoutMs: 10, fetch: async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode('{')) },
  }), { headers: { 'Content-Type': 'application/json' } }) })
  assert.equal((await session.login(input)).code, 'TIMEOUT')
  assert.equal(session.read().authState.session, null)
})

test('invalid input and outdated revisions never call the backend', async () => {
  const session = createAuthSession({ fetch: () => { throw Error('Must not fetch') } })
  for (const value of [null, {}, { ...input, expectedRevision: -1 }, { ...input, password: '' }]) {
    assert.equal((await session.login(value)).code, 'INVALID_REQUEST')
  }
  assert.equal((await session.login({ ...input, expectedRevision: 1 })).code, 'SESSION_CHANGED')
  assert.equal(session.clear(null).code, 'INVALID_REQUEST')
})

test('fresh Main instances cannot recover credentials from an earlier process', async () => {
  const first = createAuthSession({ now: () => NOW, fetch: async () => response(tokens()) })
  await first.login(input)
  const second = createAuthSession({ fetch: () => { throw Error('Must not fetch') } })
  assert.deepEqual(second.read().authState, { revision: 0, session: null })
})

test('generic auth route detection includes encoded and path-parameter aliases', () => {
  for (const path of ['/api/auth/login', '/api/auth/refresh', '/api/auth/login/', '/api/%61uth/login',
    '/api//auth/login', '/api/auth;anything/login', '/api%2fauth%2flogin', '/api/auth/logout']) {
    assert.equal(isAuthPath(path), true)
  }
  assert.equal(isAuthPath('/api/application'), false)
})

const rotationToken = n => Buffer.alloc(32, n).toString('base64url')
function renewalSetup(handler) {
  let time = NOW
  const calls = []
  const session = createAuthSession({ now: () => time, timeoutMs: 30, fetch: async (url, options) => {
    const path = new URL(url).pathname.split('/').at(-1)
    calls.push(path)
    if (path === 'login') return response(tokens({ refreshToken: rotationToken(1) }))
    return handler(path, options)
  } })
  return { session, calls, expire: () => { time = NOW + 301_000 } }
}

test('expired concurrent callers share exactly one RT consumption and one complete rotation', async () => {
  const pending = deferred()
  const c = renewalSetup((path, options) => {
    assert.equal(path, 'refresh')
    assert.equal(JSON.parse(options.body).refreshToken, rotationToken(1))
    return pending.promise
  })
  await c.session.login(input); c.expire()
  const first = c.session.ensure({ expectedRevision: 1 })
  const second = c.session.ensure({ expectedRevision: 1, forceRefresh: true })
  assert.strictEqual(first, second)
  pending.resolve(response(tokens({ accessToken: 'rotated-at', refreshToken: rotationToken(2) })))
  const result = await first
  assert.equal(result.status, 'ok')
  assert.equal(result.authState.revision, 2)
  assert.equal(result.authState.session.accessToken, 'rotated-at')
  assert.equal(result.authState.session.sessionId, '7')
  assert.equal(result.authState.session.sessionExpiresAt, NOW + 86_400_000)
  noSecret(result)
  assert.equal((await c.session.ensure({ expectedRevision: 1, forceRefresh: true })).status, 'ok')
  assert.deepEqual(c.calls, ['login', 'refresh'])
})

test('a valid AT needs no refresh, while a current 401 may force exactly one refresh', async () => {
  const c = renewalSetup(() => response(tokens({ refreshToken: rotationToken(2) })))
  await c.session.login(input)
  assert.equal((await c.session.ensure({ expectedRevision: 1 })).status, 'ok')
  assert.deepEqual(c.calls, ['login'])
  assert.equal((await c.session.ensure({ expectedRevision: 1, forceRefresh: true })).status, 'ok')
  assert.deepEqual(c.calls, ['login', 'refresh'])
})

for (const result of [
  () => Response.json({}, { status: 401 }),
  () => Response.json({}, { status: 503 }),
  () => response(tokens({ sessionId: '8', refreshToken: rotationToken(2) })),
  () => response(tokens({ sessionExpiresAt: new Date(NOW + 90_000_000).toISOString(), refreshToken: rotationToken(2) })),
  () => response(tokens({ refreshToken: rotationToken(1) })),
]) {
  test('invalid refresh, changed session, changed deadline or reused RT ends the local session without retry', async () => {
    const c = renewalSetup(() => result())
    await c.session.login(input); c.expire()
    const failed = await c.session.ensure({ expectedRevision: 1 })
    assert.equal(failed.status, 'reauthenticate')
    assert.deepEqual(failed.authState, { revision: 2, session: null })
    noSecret(failed)
    await c.session.ensure({ expectedRevision: 2 })
    assert.deepEqual(c.calls, ['login', 'refresh'])
  })
}

test('unknown refresh outcome clears and cannot be resurrected by its late response', async () => {
  const pending = deferred()
  const c = renewalSetup(() => pending.promise)
  await c.session.login(input); c.expire()
  assert.equal((await c.session.ensure({ expectedRevision: 1 })).status, 'reauthenticate')
  pending.resolve(response(tokens({ refreshToken: rotationToken(2) })))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(c.session.read().authState.session, null)
  assert.deepEqual(c.calls, ['login', 'refresh'])
})

test('logout captures the original expired AT, clears immediately, and shares the revocation result', async () => {
  const pending = deferred()
  const c = renewalSetup((path, options) => {
    assert.equal(path, 'logout')
    assert.equal(options.headers.Authorization, 'Bearer fixture-at')
    assert.equal(options.body, undefined)
    return pending.promise
  })
  await c.session.login(input); c.expire()
  const first = c.session.logout({ expectedRevision: 1 })
  const second = c.session.logout({ expectedRevision: 1 })
  assert.strictEqual(first, second)
  assert.deepEqual(c.session.read().authState, { revision: 2, session: null })
  pending.resolve(Response.json({ code: 'SUCCESS', data: null }))
  assert.equal((await first).serverRevocation, 'confirmed')
  assert.deepEqual(c.calls, ['login', 'logout'])
})

test('failed server revocation remains a successful local logout with explicit uncertainty', async () => {
  const c = renewalSetup(() => Response.json({}, { status: 503 }))
  await c.session.login(input)
  const result = await c.session.logout({ expectedRevision: 1 })
  assert.equal(result.status, 'ok')
  assert.equal(result.serverRevocation, 'unconfirmed')
  assert.equal(c.session.read().authState.session, null)
  noSecret(result)
})

test('logout wins over pending refresh; its late response cannot restore credentials', async () => {
  const pending = deferred()
  const c = renewalSetup(path => path === 'refresh' ? pending.promise : Response.json({ code: 'SUCCESS', data: null }))
  await c.session.login(input); c.expire()
  const refresh = c.session.ensure({ expectedRevision: 1 })
  await c.session.logout({ expectedRevision: 1 })
  pending.resolve(response(tokens({ refreshToken: rotationToken(2) })))
  assert.equal((await refresh).code, 'SESSION_CHANGED')
  assert.equal(c.session.read().authState.session, null)
})

test('a new login wins over old refresh and old logout acknowledgements', async () => {
  const refreshPending = deferred(), logoutPending = deferred()
  const c = renewalSetup(path => path === 'refresh' ? refreshPending.promise : logoutPending.promise)
  await c.session.login(input); c.expire()
  const refresh = c.session.ensure({ expectedRevision: 1 })
  const logout = c.session.logout({ expectedRevision: 1 })
  await c.session.login({ ...input, expectedRevision: 2 })
  refreshPending.resolve(response(tokens({ refreshToken: rotationToken(2) })))
  logoutPending.resolve(Response.json({ code: 'SUCCESS', data: null }))
  assert.equal((await refresh).code, 'SESSION_CHANGED')
  await logout
  assert.equal(c.session.read().authState.revision, 3)
  assert.notEqual(c.session.read().authState.session, null)
})

test('logout may target its own just-rotated session, but never a different login', async () => {
  const c = renewalSetup(path => path === 'refresh' ? response(tokens({ refreshToken: rotationToken(2) })) : Response.json({ code: 'SUCCESS', data: null }))
  await c.session.login(input); c.expire()
  await c.session.ensure({ expectedRevision: 1 })
  assert.equal((await c.session.logout({ expectedRevision: 1 })).serverRevocation, 'confirmed')
  await c.session.login({ ...input, expectedRevision: 3 })
  assert.equal((await c.session.logout({ expectedRevision: 1 })).code, 'SESSION_CHANGED')
  assert.notEqual(c.session.read().authState.session, null)
})

test('a read that expires the session still lets its old revision receive a reauthentication result', async () => {
  let now = NOW
  const session = createAuthSession({ now: () => now, fetch: async () => response(tokens()) })
  await session.login(input)
  now = NOW + 86_400_000
  assert.equal(session.read().authState.session, null)
  assert.equal((await session.ensure({ expectedRevision: 1 })).status, 'reauthenticate')
})

test('an unconfirmed revocation remains visible to a fresh Renderer read until a new login', async () => {
  const c = renewalSetup(() => Response.json({}, { status: 503 }))
  await c.session.login(input)
  await c.session.logout({ expectedRevision: 1 })
  assert.equal(c.session.read().serverRevocation, 'unconfirmed')
  c.session.clear({ expectedRevision: 2 })
  assert.equal(c.session.read().serverRevocation, 'unconfirmed')
  await c.session.login({ ...input, expectedRevision: 3 })
  assert.equal(c.session.read().serverRevocation, undefined)
})
