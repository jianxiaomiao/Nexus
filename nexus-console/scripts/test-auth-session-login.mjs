import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import { createPinia } from 'pinia'
import { useUserStore } from '../src/stores/userStore.ts'

const original = Object.getOwnPropertyDescriptor(globalThis, 'window')
afterEach(() => {
  if (original) Object.defineProperty(globalThis, 'window', original)
  else delete globalThis.window
})
const response = { accessToken: 'fixture-login-token', tokenType: 'Bearer', expiresInSeconds: 300 }
function setup(write, clear) {
  Object.defineProperty(globalThis, 'window', { configurable: true, value: write ? {
    nexusDesktop: { writeAuthSession: write, clearAuthSession: clear },
  } : {} })
  const store = useUserStore(createPinia())
  store.applyMainState({ revision: 4, session: null })
  store.initializationStatus = 'ready'
  return store
}
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
const saved = input => ({ status: 'ok', code: 'SUCCESS', authState: {
  revision: input.expectedRevision + 1, session: input.session,
} })

test('Web login remains in memory', async () => {
  const store = setup()
  const result = await store.completeLogin(store.beginLogin(), response)
  assert.equal(result.status, 'ok')
  assert.equal(store.accessToken, response.accessToken)
})

test('desktop waits for Main before applying credentials and preserves computed expiry', async t => {
  let now = 1_000_000
  t.mock.method(Date, 'now', () => now)
  const pending = deferred()
  let input
  const store = setup(value => { input = value; return pending.promise })
  const task = store.completeLogin(store.beginLogin(), response)
  assert.equal(store.accessToken, '')
  assert.equal(input.expectedRevision, 4)
  assert.equal(input.session.expiresAt, 1_300_000)
  now += 1000
  pending.resolve(saved(input))
  assert.equal((await task).status, 'ok')
  assert.equal(store.expiresAt, 1_300_000)
  assert.equal(store.mainRevision, 5)
})

for (const code of ['FORBIDDEN', 'SESSION_CHANGED']) {
  test(`Main ${code} does not establish a local login`, async () => {
    const store = setup(async () => ({ status: 'failed', code }))
    assert.equal((await store.completeLogin(store.beginLogin(), response)).status, 'failed')
    assert.equal(store.accessToken, '')
    assert.equal(store.mainRevision, 4)
  })
}

test('IPC rejection is an explicit failure', async () => {
  const store = setup(async () => { throw Error('Controlled rejection') })
  assert.deepEqual(await store.completeLogin(store.beginLogin(), response), { status: 'failed', code: 'SAVE_FAILED' })
  assert.equal(store.accessToken, '')
})

test('a stale HTTP completion never writes Main', async () => {
  let writes = 0
  const store = setup(async input => { writes++; return saved(input) })
  const old = store.beginLogin()
  store.beginLogin()
  assert.equal((await store.completeLogin(old, response)).status, 'stale')
  assert.equal(writes, 0)
})

test('local clear while Main write is pending removes only the acknowledged revision', async () => {
  const pending = deferred()
  let input, cleared
  const store = setup(value => { input = value; return pending.promise }, async value => {
    cleared = value
    return { status: 'ok', authState: { revision: 6, session: null } }
  })
  const task = store.completeLogin(store.beginLogin(), response)
  store.clearSession()
  pending.resolve(saved(input))
  assert.equal((await task).status, 'stale')
  assert.deepEqual(cleared, { expectedRevision: 5 })
  assert.equal(store.accessToken, '')
  assert.equal(store.mainRevision, 6)
})

test('stale-write cleanup does not retry against a newer Main session', async () => {
  const pending = deferred()
  let input, clears = 0
  const store = setup(value => { input = value; return pending.promise }, async value => {
    clears++
    assert.equal(value.expectedRevision, 5)
    return { status: 'failed', code: 'SESSION_CHANGED' }
  })
  const task = store.completeLogin(store.beginLogin(), response)
  store.beginLogin()
  pending.resolve(saved(input))
  assert.equal((await task).status, 'stale')
  assert.equal(clears, 1)
  assert.equal(store.accessToken, '')
})

test('invalid lifetime is rejected before IPC', async () => {
  let writes = 0
  const store = setup(async input => { writes++; return saved(input) })
  const result = await store.completeLogin(store.beginLogin(), { ...response, expiresInSeconds: Infinity })
  assert.equal(result.status, 'failed')
  assert.equal(writes, 0)
})
