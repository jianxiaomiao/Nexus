import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import { createPinia } from 'pinia'
import { useUserStore } from '../src/stores/userStore.ts'

const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
afterEach(() => {
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else delete globalThis.window
})
const session = { accessToken: 'fixture-token', tokenType: 'Bearer', sessionId: '1', sessionExpiresAt: 9_000_000_000_000, expiresAt: Date.now() + 600000 }
const response = { accessToken: 'fixture-new-token', tokenType: 'Bearer', expiresInSeconds: 300 }
const empty = revision => ({ status: 'ok', code: 'SUCCESS', authState: { revision, session: null } })
const changed = { status: 'failed', code: 'SESSION_CHANGED' }
const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function setup(clear, read) {
  Object.defineProperty(globalThis, 'window', { configurable: true, value: clear ? {
    nexusDesktop: { clearAuthSession: clear, logoutAuthSession: clear, readAuthSession: read },
  } : {} })
  const store = useUserStore(createPinia())
  store.applyMainState({ revision: 4, session })
  store.initializationStatus = 'ready'
  return store
}
const context = store => ({ operationVersion: store.operationVersion, mainRevision: store.mainRevision })

test('logout immediately clears locally, waits for Main, and deduplicates', async () => {
  const pending = deferred()
  let calls = 0
  const store = setup(input => { calls++; assert.deepEqual(input, { expectedRevision: 4 }); return pending.promise })
  const first = store.invalidateSession({ reason: 'logout' })
  const second = store.invalidateSession({ reason: 'logout' })
  assert.equal(store.clearing, true)
  assert.equal(store.accessToken, '')
  assert.equal(store.mainRevision, 4)
  await Promise.resolve()
  assert.equal(calls, 1)
  const blocked = store.beginLogin()
  assert.equal((await store.completeLogin(blocked, response)).code, 'CLEAR_PENDING')
  pending.resolve(empty(5))
  assert.equal((await first).status, 'ok')
  assert.equal((await second).status, 'ok')
  assert.equal(store.mainRevision, 5)
  assert.equal(store.clearing, false)
})

test('old 401 is ignored before any local mutation or IPC', async () => {
  let calls = 0
  const store = setup(async () => { calls++; return empty(5) })
  const old = context(store)
  store.beginLogin()
  const version = store.operationVersion
  assert.equal((await store.invalidateSession({ reason: 'unauthorized', context: old })).status, 'stale')
  assert.equal(store.accessToken, session.accessToken)
  assert.equal(store.operationVersion, version)
  assert.equal(calls, 0)
})

test('nonexpired session is not invalidated by an expiry signal', async () => {
  const store = setup(async () => { throw Error('Unexpected IPC') })
  assert.equal((await store.invalidateSession({ reason: 'expired', context: context(store) })).status, 'stale')
  assert.equal(store.accessToken, session.accessToken)
})

test('matching 401 and duplicate signals share a single clear', async () => {
  const pending = deferred()
  let calls = 0
  const store = setup(() => { calls++; return pending.promise })
  const input = { reason: 'unauthorized', context: context(store) }
  const first = store.invalidateSession(input)
  const second = store.invalidateSession(input)
  await Promise.resolve()
  pending.resolve(empty(5))
  assert.equal((await first).status, 'ok')
  assert.equal((await second).status, 'ok')
  assert.equal(calls, 1)
})

for (const alreadyEmpty of [true, false]) {
  test(`conflict read sees ${alreadyEmpty ? 'empty' : 'new session'} and never retries clear`, async () => {
    let clears = 0, reads = 0
    const store = setup(async () => { clears++; return changed }, async () => {
      reads++
      return { status: 'ok', authState: { revision: 6, session: alreadyEmpty ? null : session } }
    })
    const result = await store.invalidateSession({ reason: 'logout' })
    assert.equal(result.status, alreadyEmpty ? 'ok' : 'failed')
    if (!alreadyEmpty) assert.equal(result.code, 'SESSION_CHANGED')
    assert.equal(store.accessToken, '')
    assert.equal(store.mainRevision, alreadyEmpty ? 6 : 4)
    assert.equal(clears, 1)
    assert.equal(reads, 1)
  })
}

test('IPC failure is visible, blocks login establishment, and supports explicit retry', async () => {
  let failing = true
  const store = setup(async () => { if (failing) throw Error('Controlled IPC error'); return empty(5) })
  assert.equal((await store.invalidateSession({ reason: 'logout' })).code, 'CLEAR_FAILED')
  assert.ok(store.clearError)
  assert.equal(store.clearing, false)
  assert.equal((await store.completeLogin(store.beginLogin(), response)).code, 'CLEAR_PENDING')
  failing = false
  assert.equal((await store.invalidateSession({ reason: 'logout' })).status, 'ok')
  assert.equal(store.clearError, null)
})

test('late clear acknowledgement cannot erase a newer local login', async () => {
  const pending = deferred()
  const store = setup(() => pending.promise)
  const task = store.invalidateSession({ reason: 'logout' })
  await Promise.resolve()
  store.setSession(response)
  pending.resolve(empty(5))
  assert.equal((await task).status, 'stale')
  assert.equal(store.accessToken, response.accessToken)
  assert.equal(store.mainRevision, 5)
})

test('Web logout has no Main dependency', async () => {
  const store = setup()
  assert.equal((await store.invalidateSession({ reason: 'logout' })).status, 'ok')
  assert.equal(store.accessToken, '')
  assert.equal(store.clearing, false)
})

test('bad successful acknowledgement is not accepted', async () => {
  const store = setup(async () => ({ status: 'ok', authState: { revision: 5, session } }))
  assert.equal((await store.invalidateSession({ reason: 'logout' })).code, 'CLEAR_FAILED')
  assert.equal(store.mainRevision, 4)
})

test('current expired session is cleared using its exact Main revision', async () => {
  let calls = 0
  const store = setup(async input => { calls++; assert.equal(input.expectedRevision, 4); return empty(5) })
  store.expiresAt = Date.now() - 1
  assert.equal((await store.invalidateSession({ reason: 'expired', context: context(store) })).status, 'ok')
  assert.equal(store.mainRevision, 5)
  assert.equal(store.accessToken, '')
  assert.equal(calls, 1)
})

test('logout with empty local credentials still invalidates an in-flight login', async () => {
  const store = setup(async () => empty(5))
  store.applyMainState({ revision: 4, session: null })
  const attempt = store.beginLogin()
  assert.equal((await store.invalidateSession({ reason: 'logout' })).status, 'ok')
  assert.equal((await store.completeLogin(attempt, response)).status, 'stale')
  assert.equal(store.accessToken, '')
})

test('uninitialized desktop fails explicitly rather than guessing revision zero', async () => {
  let calls = 0
  const store = setup(async () => { calls++; return empty(1) })
  store.mainRevision = null
  store.initializationStatus = 'idle'
  assert.equal((await store.invalidateSession({ reason: 'logout' })).code, 'NOT_READY')
  assert.equal(calls, 0)
  assert.ok(store.clearError)
})

for (const [name, state, expected] of [
  ['already cleared', { revision: 5, session: null }, 'ok'],
  ['original session remains', { revision: 4, session }, 'CLEAR_FAILED'],
  ['another session exists', { revision: 6, session }, 'SESSION_CHANGED'],
  ['empty but revision did not advance', { revision: 4, session: null }, 'CLEAR_FAILED'],
  ['invalid empty revision', { revision: 5.5, session: null }, 'CLEAR_FAILED'],
  ['malformed nonempty state', { revision: 6, session: {} }, 'CLEAR_FAILED'],
]) {
  test(`lost IPC acknowledgement: ${name}, read once and never re-clear`, async () => {
    let clears = 0, reads = 0
    const store = setup(async () => { clears++; throw Error('Controlled acknowledgement loss') }, async () => {
      reads++
      return { status: 'ok', authState: state }
    })
    const result = await store.invalidateSession({ reason: 'unauthorized', context: context(store) })
    assert.equal(expected === 'ok' ? result.status : result.code, expected)
    assert.equal(store.accessToken, '')
    assert.equal(store.mainRevision, expected === 'ok' ? 5 : 4)
    assert.equal(clears, 1)
    assert.equal(reads, 1)
    assert.equal(store.clearing, false)
  })
}

for (const read of [
  async () => { throw Error('Controlled read failure') },
  async () => ({ status: 'failed', code: 'FORBIDDEN' }),
]) {
  test('failed read after IPC rejection remains unconfirmed and blocks login', async () => {
    const store = setup(async () => { throw Error('Controlled clear failure') }, read)
    assert.equal((await store.invalidateSession({ reason: 'logout' })).code, 'CLEAR_FAILED')
    assert.equal(store.mainRevision, 4)
    assert.ok(store.clearError)
    assert.equal((await store.completeLogin(store.beginLogin(), response)).code, 'CLEAR_PENDING')
  })
}

test('late reconciliation cannot overwrite a newer local operation', async () => {
  const pending = deferred()
  const store = setup(async () => { throw Error('Controlled acknowledgement loss') }, () => pending.promise)
  const task = store.invalidateSession({ reason: 'logout' })
  await Promise.resolve()
  await Promise.resolve()
  store.setSession(response)
  pending.resolve(empty(5))
  assert.equal((await task).status, 'stale')
  assert.equal(store.accessToken, response.accessToken)
  assert.equal(store.mainRevision, 4)
  assert.equal(store.clearError, null)
})

test('late IPC rejection does not start reconciliation for an obsolete operation', async () => {
  let reject, reads = 0
  const pending = new Promise((resolve, fail) => { reject = fail })
  const store = setup(() => pending, async () => { reads++; return empty(5) })
  const task = store.invalidateSession({ reason: 'logout' })
  await Promise.resolve()
  store.setSession(response)
  reject(Error('Controlled late rejection'))
  assert.equal((await task).status, 'stale')
  assert.equal(reads, 0)
  assert.equal(store.accessToken, response.accessToken)
})

test('different action proxies for one Store still share the same clear', async () => {
  const pending = deferred()
  let clears = 0
  const store = setup(() => { clears++; return pending.promise })
  const input = { reason: 'unauthorized', context: context(store) }
  const first = store.invalidateSession.call(new Proxy(store, {}), input)
  const second = store.invalidateSession.call(new Proxy(store, {}), input)
  await Promise.resolve()
  assert.equal(clears, 1)
  pending.resolve(empty(5))
  assert.equal((await first).status, 'ok')
  assert.equal((await second).status, 'ok')
})

test('different action proxies for one Store share initialization too', async () => {
  const pending = deferred()
  let reads = 0
  const store = setup(async () => empty(5), () => { reads++; return pending.promise })
  store.initializationStatus = 'idle'
  const first = store.initializeSession.call(new Proxy(store, {}))
  const second = store.initializeSession.call(new Proxy(store, {}))
  await Promise.resolve()
  assert.equal(reads, 1)
  pending.resolve({ status: 'ok', authState: { revision: 4, session } })
  await Promise.all([first, second])
  assert.equal(store.initializationStatus, 'ready')
})
