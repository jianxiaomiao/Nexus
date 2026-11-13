// 只替换 Main 的网络响应；正式 handler、Preload、状态和定时器照常运行。
const { app } = require('electron')
const path = require('node:path')
const dir = process.env.NEXUS_TRACKING_TEST_DIR
if (!dir) throw Error('Use test-tracking-boundaries.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)
const control = globalThis.__trackingTest = { requests: [], ignoreAbortNext: false }
globalThis.fetch = (url, options) => {
  const parsed = new URL(url)
  if (parsed.origin !== 'http://localhost:8080' || !/^\/v1\/web\/screenshot-jobs\/[0-9a-f-]{36}$/.test(parsed.pathname)) throw Error('Unexpected test destination')
  const record = { jobId: parsed.pathname.split('/').at(-1), settled: false, aborted: false, ignoreAbort: control.ignoreAbortNext, redirectBlocked: options.redirect === 'error' }
  control.ignoreAbortNext = false
  control.requests.push(record)
  return new Promise((resolve, reject) => {
    const settle = () => { record.settled = true; options.signal.removeEventListener('abort', abort) }
    const abort = () => {
      record.aborted = true
      if (!record.ignoreAbort) { settle(); reject(new DOMException('Aborted', 'AbortError')) }
    }
    record.release = status => {
      if (record.settled) throw Error('Response already settled')
      settle()
      const job = { jobId: record.jobId, status, attemptCount: 1, createdAt: '2026-11-12T00:00:00Z', updatedAt: '2026-11-12T00:00:01Z', resultSizeBytes: null, resultSha256: null, blockedResources: null, resourceLimitReached: null, resultExpiresAt: null, failureCode: null, startedAt: null, completedAt: null }
      resolve(new Response(JSON.stringify({ code: 'SUCCESS', data: job }), { headers: { 'Content-Type': 'application/json' } }))
    }
    options.signal.addEventListener('abort', abort, { once: true })
    if (options.signal.aborted) abort()
  })
}
require('../main.cjs')
