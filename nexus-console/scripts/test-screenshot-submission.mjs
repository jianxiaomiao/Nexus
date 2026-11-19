import { test } from 'node:test'
import assert from 'node:assert/strict'
import { prepareScreenshotSubmission, screenshotSubmissionNeedsReconcile } from '../src/api/screenshotSubmission.ts'

function storage() {
  const entries = new Map()
  return { getItem: k => entries.get(k) ?? null, setItem: (k, v) => entries.set(k, v), text: () => [...entries.values()].join('') }
}
const publicId = '123e4567-e89b-42d3-a456-426614174000'
const key = `nxk_v1_${publicId}_FIXTURE_ONLY_PRIVATE_KEY`
const url = 'https://blog.csdn.net/private-fixture-path'

test('expiry, conflict, rate limit and incomplete responses cannot silently settle an earlier submission', () => {
  for (const status of [200, 201, 408, 409, 410, 429, 500, 503]) assert.equal(screenshotSubmissionNeedsReconcile(status, false), true)
  for (const status of [400, 401, 403, 404, 422]) assert.equal(screenshotSubmissionNeedsReconcile(status, false), false)
  assert.equal(screenshotSubmissionNeedsReconcile(201, true), false)
})

test('unknown submission survives reconstruction with only public metadata', async () => {
  const saved = storage()
  const first = await prepareScreenshotSubmission(key, url, saved)
  const recovered = await prepareScreenshotSubmission(key, url, saved)
  assert.equal(recovered.key, first.key)
  assert.match(first.key, /^\d{13}\.[0-9a-f-]{36}$/)
  assert.equal(saved.text().includes(key), false)
  assert.equal(saved.text().includes('FIXTURE_ONLY_PRIVATE_KEY'), false)
  assert.equal(saved.text().includes(url), false)
  assert.equal(saved.text().includes(publicId), true)
  await recovered.settle()
  assert.notEqual((await prepareScreenshotSubmission(key, url, saved)).key, first.key)
})
test('different machine identities and payloads cannot accidentally replay one operation', async () => {
  const saved = storage()
  const first = await prepareScreenshotSubmission(key, url, saved)
  const other = await prepareScreenshotSubmission(key.replace(publicId, '223e4567-e89b-42d3-a456-426614174000'), url, saved)
  const changed = await prepareScreenshotSubmission(key, url + '-other', saved)
  assert.notEqual(other.key, first.key)
  assert.notEqual(changed.key, first.key)
})
test('expired or corrupt metadata blocks dispatch instead of silently creating another job', async () => {
  const saved = storage()
  await prepareScreenshotSubmission(key, url, saved)
  const records = JSON.parse(saved.getItem('nexus.screenshot.submissions.v1'))
  records[0].key = `${Date.now() - 7 * 86400000}.${crypto.randomUUID()}`
  saved.setItem('nexus.screenshot.submissions.v1', JSON.stringify(records))
  await assert.rejects(prepareScreenshotSubmission(key, url, saved), /七天/)
  saved.setItem('nexus.screenshot.submissions.v1', '{}')
  await assert.rejects(prepareScreenshotSubmission(key, url, saved), /存储/)
})

test('explicit reconciliation is required before replacing an expired unknown submission', async () => {
  const { abandonScreenshotSubmission } = await import('../src/api/screenshotSubmission.ts')
  const saved = storage()
  const original = await prepareScreenshotSubmission(key, url, saved)
  const rows = JSON.parse(saved.getItem('nexus.screenshot.submissions.v1'))
  rows[0].key = `${Date.now() - 8 * 86400000}.${crypto.randomUUID()}`
  saved.setItem('nexus.screenshot.submissions.v1', JSON.stringify(rows))
  await abandonScreenshotSubmission(key, url, saved)
  assert.notEqual((await prepareScreenshotSubmission(key, url, saved)).key, original.key)
})
