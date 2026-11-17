import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import { createPinia } from 'pinia'
import { useUserStore } from '../src/stores/userStore.ts'

const original = Object.getOwnPropertyDescriptor(globalThis, 'window')
afterEach(() => {
  if (original) Object.defineProperty(globalThis, 'window', original)
  else delete globalThis.window
})
const webResponse = { accessToken: 'fixture-token', tokenType: 'Bearer', expiresInSeconds: 300 }
const savedResponse = () => ({ ...webResponse, authState: { revision: 5, session: {
  accessToken: webResponse.accessToken, tokenType: 'Bearer', expiresAt: Date.now() + 300_000,
  sessionId: '7', sessionExpiresAt: Date.now() + 86_400_000,
} } })
function setup(clear) {
  Object.defineProperty(globalThis, 'window', { configurable: true, value: clear ? {
    nexusDesktop: {
      clearAuthSession: clear,
      logoutAuthSession: clear,
      loginAuthSession: () => { throw Error('Completion must not request login again') },
      writeAuthSession: () => { throw Error('Legacy write must never be used') },
    },
  } : {} })
  const store = useUserStore(createPinia())
  store.applyMainState({ revision: 4, session: null })
  store.initializationStatus = 'ready'
  return store
}

test('Web login remains in memory', async () => {
  const store = setup()
  assert.equal((await store.completeLogin(store.beginLogin(), webResponse)).status, 'ok')
  assert.equal(store.accessToken, webResponse.accessToken)
})

test('desktop applies an already-saved snapshot without write IPC or recomputing expiry', async t => {
  let now = 1_000_000
  t.mock.method(Date, 'now', () => now)
  const store = setup(() => { throw Error('Unexpected clear') })
  const response = savedResponse()
  const expiresAt = response.authState.session.expiresAt
  now += 5000
  assert.equal((await store.completeLogin(store.beginLogin(), response)).status, 'ok')
  assert.equal(store.expiresAt, expiresAt)
  assert.equal(store.mainRevision, 5)
  assert.equal(store.sessionId, '7')
  assert.equal(Object.hasOwn(store.$state, 'refreshToken'), false)
})

test('desktop refuses a legacy AT-only response', async () => {
  const store = setup(() => { throw Error('Unexpected clear') })
  assert.equal((await store.completeLogin(store.beginLogin(), webResponse)).code, 'INVALID_RESPONSE')
  assert.equal(store.accessToken, '')
})

for (const mutate of [
  r => { r.authState.revision = 4 },
  r => { r.authState.session.sessionId = '' },
  r => { r.authState.session.sessionExpiresAt = 0 },
  r => { r.authState.session = null },
]) {
  test('invalid public snapshot does not establish a local login', async () => {
    const store = setup(() => { throw Error('Unexpected clear') })
    const response = savedResponse()
    mutate(response)
    assert.equal((await store.completeLogin(store.beginLogin(), response)).status, 'failed')
    assert.equal(store.accessToken, '')
  })
}

test('stale saved result compensates only its acknowledged revision', async () => {
  let cleared
  const store = setup(async input => {
    cleared = input
    return { status: 'ok', authState: { revision: 6, session: null } }
  })
  const attempt = store.beginLogin()
  store.clearSession()
  assert.equal((await store.completeLogin(attempt, savedResponse())).status, 'stale')
  assert.deepEqual(cleared, { expectedRevision: 5 })
  assert.equal(store.mainRevision, 6)
  assert.equal(store.accessToken, '')
})

test('stale compensation never chases a newer Main revision', async () => {
  let clears = 0
  const store = setup(async input => {
    clears++
    assert.equal(input.expectedRevision, 5)
    return { status: 'failed', code: 'SESSION_CHANGED' }
  })
  const old = store.beginLogin()
  store.beginLogin()
  assert.equal((await store.completeLogin(old, savedResponse())).status, 'stale')
  assert.equal(clears, 1)
  assert.equal(store.accessToken, '')
})

test('expired saved result is cleared at its exact revision', async () => {
  const store = setup(async () => ({ status: 'ok', authState: { revision: 6, session: null } }))
  const response = savedResponse()
  response.authState.session.expiresAt = Date.now() - 1
  assert.equal((await store.completeLogin(store.beginLogin(), response)).code, 'SAVE_FAILED')
  assert.equal(store.accessToken, '')
})

test('Web invalid lifetime is rejected', async () => {
  const store = setup()
  assert.equal((await store.completeLogin(store.beginLogin(), { ...webResponse, expiresInSeconds: Infinity })).status, 'failed')
})
