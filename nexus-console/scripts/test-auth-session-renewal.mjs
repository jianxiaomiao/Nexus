import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import { createPinia } from 'pinia'
import { useUserStore } from '../src/stores/userStore.ts'

const original = Object.getOwnPropertyDescriptor(globalThis, 'window')
afterEach(() => original ? Object.defineProperty(globalThis, 'window', original) : delete globalThis.window)
const session = () => ({ accessToken: 'old-at', tokenType: 'Bearer', expiresAt: Date.now() - 1,
  sessionId: '7', sessionExpiresAt: Date.now() + 600_000 })
const next = () => ({ status: 'ok', authState: { revision: 2, session: { ...session(), accessToken: 'new-at', expiresAt: Date.now() + 300_000 } } })
const context = store => ({ operationVersion: store.operationVersion, mainRevision: store.mainRevision })
function setup(ensure, logout) {
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { nexusDesktop: {
    ensureAuthSession: ensure, logoutAuthSession: logout,
  } } })
  const store = useUserStore(createPinia())
  store.applyMainState({ revision: 1, session: session() })
  store.initializationStatus = 'ready'
  return store
}
function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

test('concurrent callers and action proxies share one renewal and apply the original Main deadline', async () => {
  const pending = deferred()
  let calls = 0
  const store = setup(() => { calls++; return pending.promise })
  const ctx = context(store)
  const first = store.ensureSession.call(new Proxy(store, {}), ctx)
  const second = store.ensureSession.call(new Proxy(store, {}), ctx, true)
  assert.equal(calls, 1)
  const result = next()
  pending.resolve(result)
  assert.equal((await first).status, 'ok')
  assert.equal((await second).status, 'ok')
  assert.equal(store.expiresAt, result.authState.session.expiresAt)
  assert.equal(store.mainRevision, 2)
  assert.equal(store.accessToken, 'new-at')
  assert.equal((await store.ensureSession(ctx, true)).status, 'ok')
  assert.equal(calls, 1) // An old AT's late 401 does not rotate the new RT again.
})

test('renewal completion after a new login intent cannot update local credentials', async () => {
  const pending = deferred()
  const store = setup(() => pending.promise)
  const request = store.ensureSession(context(store))
  store.beginLogin()
  pending.resolve(next())
  assert.equal((await request).status, 'stale')
  assert.equal(store.accessToken, 'old-at')
  assert.equal(store.mainRevision, 1)
})

test('confirmed refresh failure clears locally and carries a reauthentication notice', async () => {
  const store = setup(async () => ({ status: 'reauthenticate', code: 'TIMEOUT', authState: { revision: 2, session: null } }))
  const result = await store.ensureSession(context(store))
  assert.equal(result.status, 'reauthenticate')
  assert.equal(store.accessToken, '')
  assert.equal(store.mainRevision, 2)
  assert.ok(store.sessionNotice)
  assert.equal(store.clearError, null)
})

test('lost renewal IPC stops the original Main session, never retries the RT, and requests login', async () => {
  let renewals = 0, logouts = 0
  const store = setup(async () => { renewals++; throw Error('Lost IPC acknowledgement') }, async input => {
    logouts++
    assert.equal(input.expectedRevision, 1)
    return { status: 'ok', authState: { revision: 3, session: null }, serverRevocation: 'unconfirmed' }
  })
  assert.equal((await store.ensureSession(context(store))).status, 'reauthenticate')
  assert.equal(renewals, 1)
  assert.equal(logouts, 1)
  assert.equal(store.mainRevision, 3)
  assert.equal(store.accessToken, '')
})

test('malformed or wrong-session renewal cannot establish credentials', async () => {
  const value = next()
  value.authState.session.sessionId = '8'
  const store = setup(async () => value)
  assert.equal((await store.ensureSession(context(store))).status, 'failed')
  assert.equal(store.accessToken, 'old-at')
  assert.ok(store.clearError)
})

test('logout unconfirmed still clears locally, preserves the Main empty revision and shows a warning', async () => {
  const store = setup(null, async () => ({ status: 'ok', authState: { revision: 2, session: null }, serverRevocation: 'unconfirmed' }))
  assert.equal((await store.invalidateSession({ reason: 'logout' })).status, 'ok')
  assert.equal(store.accessToken, '')
  assert.equal(store.mainRevision, 2)
  assert.match(store.sessionNotice, /服务端撤销未确认/)
  assert.equal(store.clearError, null)
})

test('logout acknowledgement from the old session cannot clear a new local login', async () => {
  const pending = deferred()
  const store = setup(null, () => pending.promise)
  const logout = store.invalidateSession({ reason: 'logout' })
  await Promise.resolve()
  store.setSession({ accessToken: 'different-local-at', tokenType: 'Bearer', expiresInSeconds: 300 })
  pending.resolve({ status: 'ok', authState: { revision: 2, session: null }, serverRevocation: 'unconfirmed' })
  assert.equal((await logout).status, 'stale')
  assert.equal(store.accessToken, 'different-local-at')
  assert.equal(store.sessionNotice, null)
})

test('a later local-only logout cannot erase an earlier unconfirmed-revocation warning', async () => {
  let calls = 0
  const store = setup(null, async () => ({ status: 'ok',
    authState: { revision: ++calls + 1, session: null },
    serverRevocation: calls === 1 ? 'unconfirmed' : 'not-needed' }))
  await store.invalidateSession({ reason: 'logout' })
  await store.invalidateSession({ reason: 'logout' })
  assert.match(store.sessionNotice, /服务端撤销未确认/)
})

test('a fresh Renderer restores the unconfirmed-revocation warning without restoring credentials', async () => {
  const store = setup(null, null)
  window.nexusDesktop.readAuthSession = async () => ({ status: 'ok', authState: { revision: 2, session: null }, serverRevocation: 'unconfirmed' })
  store.initializationStatus = 'idle'
  await store.initializeSession()
  assert.equal(store.accessToken, '')
  assert.match(store.sessionNotice, /服务端撤销未确认/)
})
