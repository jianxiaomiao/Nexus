import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { createPinia } from 'pinia'
import { useUserStore } from '../src/stores/userStore.ts'

const now = 1_000_000
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
const stores = []
const session = { accessToken: 'fixture-restored-token', tokenType: 'Bearer', sessionId: '1', sessionExpiresAt: 9_000_000_000_000, expiresAt: now + 120_000 }
const login = { accessToken: 'fixture-new-login', tokenType: 'Bearer', expiresInSeconds: 300 }

afterEach(() => {
  for (const store of stores.splice(0)) store.$dispose()
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else delete globalThis.window
})

function createStore(t) {
  t.mock.method(Date, 'now', () => now)
  const store = useUserStore(createPinia())
  stores.push(store)
  return store
}

function installBridge(read) {
  const counts = { reads: 0, writes: 0, clears: 0 }
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    nexusDesktop: {
      readAuthSession: () => { counts.reads++; return read() },
      loginAuthSession: () => { counts.writes++; throw Error('Initialization must not write') },
      clearAuthSession: () => { counts.clears++; throw Error('Initialization must not clear Main') },
    },
  } })
  return counts
}

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('Web initialization preserves memory state; a fresh Store does not recover it', async t => {
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {} })
  const store = createStore(t)
  store.setSession(login)
  await store.initializeSession()
  assert.equal(store.initializationStatus, 'ready')
  assert.equal(store.mainRevision, null)
  assert.equal(store.accessToken, login.accessToken)
  const fresh = createStore(t)
  await fresh.initializeSession()
  assert.equal(fresh.accessToken, '')
  assert.equal(fresh.mainRevision, null)
})

test('desktop restores original expiry and revision without writing back', async t => {
  const counts = installBridge(async () => ({ status: 'ok', authState: { revision: 4, session } }))
  const store = createStore(t)
  await store.initializeSession()
  assert.equal(store.initializationStatus, 'ready')
  assert.equal(store.initializationError, null)
  assert.equal(store.mainRevision, 4)
  assert.equal(store.accessToken, session.accessToken)
  assert.equal(store.expiresAt, session.expiresAt)
  assert.equal(store.expiresInSeconds, 120)
  await store.initializeSession()
  assert.deepEqual(counts, { reads: 1, writes: 0, clears: 0 })
})

test('concurrent initialization shares one read', async t => {
  const pending = deferred()
  const counts = installBridge(() => pending.promise)
  const store = createStore(t)
  const first = store.initializeSession()
  const second = store.initializeSession()
  assert.equal(store.initializationStatus, 'loading')
  await Promise.resolve()
  assert.equal(counts.reads, 1)
  pending.resolve({ status: 'ok', authState: { revision: 2, session } })
  await Promise.all([first, second])
  assert.equal(store.mainRevision, 2)
  assert.equal(store.initializationStatus, 'ready')
})

test('different Pinia instances do not share pending initialization', async t => {
  const pending = deferred()
  const counts = installBridge(() => pending.promise)
  const first = createStore(t)
  const second = createStore(t)
  const tasks = [first.initializeSession(), second.initializeSession()]
  await Promise.resolve()
  assert.equal(counts.reads, 2)
  pending.resolve({ status: 'ok', authState: { revision: 1, session: null } })
  await Promise.all(tasks)
  assert.equal(first.mainRevision, 1)
  assert.equal(second.mainRevision, 1)
})

test('successful empty snapshot clears local fields and retains Main revision', async t => {
  const counts = installBridge(async () => ({ status: 'ok', code: 'SESSION_IS_NULL', authState: { revision: 7, session: null } }))
  const store = createStore(t)
  store.setSession(login)
  const version = store.operationVersion
  await store.initializeSession()
  assert.equal(store.mainRevision, 7)
  assert.equal(store.accessToken, '')
  assert.equal(store.expiresAt, 0)
  assert.equal(store.operationVersion, version)
  assert.equal(store.initializationStatus, 'ready')
  assert.deepEqual(counts, { reads: 1, writes: 0, clears: 0 })
})

test('expired snapshot retains identity for Router renewal but is not an authenticated session', async t => {
  const counts = installBridge(async () => ({ status: 'ok', authState: { revision: 8, session: { ...session, expiresAt: now } } }))
  const store = createStore(t)
  await store.initializeSession()
  assert.equal(store.mainRevision, 8)
  assert.equal(store.accessToken, session.accessToken)
  assert.equal(store.expiresAt <= now, true)
  assert.equal(store.initializationStatus, 'ready')
  assert.deepEqual(counts, { reads: 1, writes: 0, clears: 0 })
})

for (const action of ['login', 'clear']) {
  test(`late initialization cannot overwrite a newer local ${action}`, async t => {
    const pending = deferred()
    installBridge(() => pending.promise)
    const store = createStore(t)
    const task = store.initializeSession()
    await Promise.resolve()
    if (action === 'login') store.setSession(login)
    else store.clearSession()
    pending.resolve({ status: 'ok', authState: { revision: 4, session } })
    await task
    assert.equal(store.accessToken, action === 'login' ? login.accessToken : '')
    assert.equal(store.mainRevision, null)
    assert.equal(store.initializationStatus, 'idle')
    assert.equal(store.initializationError, null)
  })
}

test('late failure does not mark a newer local login as failed', async t => {
  const pending = deferred()
  installBridge(() => pending.promise)
  const store = createStore(t)
  const task = store.initializeSession()
  await Promise.resolve()
  store.setSession(login)
  pending.reject(Error('Controlled transport failure'))
  await task
  assert.equal(store.accessToken, login.accessToken)
  assert.equal(store.initializationStatus, 'idle')
  assert.equal(store.initializationError, null)
})

for (const failure of ['forbidden', 'rejection']) {
  test(`${failure} is distinct from empty session and allows explicit retry`, async t => {
    let fail = true
    const counts = installBridge(async () => {
      if (!fail) return { status: 'ok', authState: { revision: 3, session } }
      if (failure === 'forbidden') return { status: 'failed', code: 'FORBIDDEN' }
      throw Error('Controlled transport failure; do not expose raw details')
    })
    const store = createStore(t)
    await store.initializeSession()
    assert.equal(store.initializationStatus, 'failed')
    assert.equal(store.mainRevision, null)
    assert.equal(store.accessToken, '')
    assert.equal(store.initializationError, '无法恢复桌面登录状态，请重试。')
    fail = false
    await store.initializeSession()
    assert.equal(store.initializationStatus, 'ready')
    assert.equal(store.initializationError, null)
    assert.equal(store.mainRevision, 3)
    assert.deepEqual(counts, { reads: 2, writes: 0, clears: 0 })
  })
}

test('an older desktop bridge is reported as failure, not treated as Web', async t => {
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { nexusDesktop: {} } })
  const store = createStore(t)
  await store.initializeSession()
  assert.equal(store.initializationStatus, 'failed')
  assert.equal(store.mainRevision, null)
})

test('malformed Main state is rejected before changing local fields', async t => {
  installBridge(async () => ({ status: 'ok', authState: { revision: -1, session } }))
  const store = createStore(t)
  await store.initializeSession()
  assert.equal(store.initializationStatus, 'failed')
  assert.equal(store.mainRevision, null)
  assert.equal(store.accessToken, '')
})

test('a newer Main snapshot applied during initialization survives an older read', async t => {
  const pending = deferred()
  const counts = installBridge(() => pending.promise)
  const store = createStore(t)
  const task = store.initializeSession()
  await Promise.resolve()
  store.applyMainState({ revision: 6, session: { ...session, accessToken: 'fixture-newer-main' } })
  pending.resolve({ status: 'ok', authState: { revision: 5, session } })
  await task
  assert.equal(store.mainRevision, 6)
  assert.equal(store.accessToken, 'fixture-newer-main')
  assert.equal(store.initializationStatus, 'ready')
  assert.deepEqual(counts, { reads: 1, writes: 0, clears: 0 })
})

test('applying Main state copies fields and cannot roll back a known revision', t => {
  const store = createStore(t)
  const snapshot = { revision: 10, session: { ...session } }
  assert.equal(store.applyMainState(snapshot), true)
  snapshot.session.accessToken = 'fixture-mutated-input'
  assert.equal(store.accessToken, session.accessToken)
  assert.equal(store.applyMainState({ revision: 9, session: null }), false)
  assert.equal(store.mainRevision, 10)
  assert.equal(store.accessToken, session.accessToken)
})
