import { test } from 'node:test'
import assert from 'node:assert/strict'
import { withNetworkDeadline } from '../src/api/networkDeadline.ts'

test('deadline rejects even when the connection or body ignores abort', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let signal
  const pending = withNetworkDeadline(undefined, 15000, async current => {
    signal = current
    return new Promise(() => {})
  })
  await Promise.resolve()
  const rejected = assert.rejects(pending, { name: 'TimeoutError' })
  t.mock.timers.tick(15000)
  await rejected
  assert.equal(signal.aborted, true)
})
test('external cancellation and successful completion both release the exchange', async () => {
  const controller = new AbortController()
  const pending = withNetworkDeadline(controller.signal, 15000, () => new Promise(() => {}))
  controller.abort()
  await assert.rejects(pending, { name: 'AbortError' })
  let signal
  assert.equal(await withNetworkDeadline(undefined, 15000, async current => { signal = current; return 7 }), 7)
  assert.equal(signal.aborted, true)
})
