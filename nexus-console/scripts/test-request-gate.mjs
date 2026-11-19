import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequestGate } from '../src/utils/requestGate.ts'

test('a late success, failure or finally never owns the latest state', async () => {
  const gate = createRequestGate()
  const old = gate.begin(), current = gate.begin()
  const state = { data: 'new', error: '', loading: true }
  for (const finish of [() => state.data = 'old', () => state.error = 'old', () => state.loading = false]) {
    if (old()) finish()
  }
  assert.deepEqual(state, { data: 'new', error: '', loading: true })
  assert.equal(current(), true)
})
test('invalidation and disposal reject all previous completions', () => {
  const gate = createRequestGate(), a = gate.begin()
  gate.invalidate(); assert.equal(a(), false)
  const b = gate.begin(); gate.dispose(); assert.equal(b(), false)
  assert.equal(gate.begin()(), false)
})
