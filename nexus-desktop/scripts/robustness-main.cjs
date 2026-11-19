const { app } = require('electron')
const path = require('node:path')
const crypto = require('node:crypto')
const directory = process.env.NEXUS_ROBUSTNESS_DIR
const runtime = path.resolve(__dirname, '../.runtime')
const relative = directory && path.relative(runtime, path.resolve(directory))
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Use test-robustness.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(directory, 'profile') : value)
const c = globalThis.__robustness = { polls: [], submissions: [], imageReads: 0 }
let sid = 0, deadline
const jobs = new Map()
const envelope = data => Response.json({ code: 'SUCCESS', data })
app.on('web-contents-created', (_, contents) => contents.session.webRequest.onBeforeRequest(
  { urls: ['http://*/*', 'https://*/*'] }, (_, callback) => callback({ cancel: true })))
globalThis.fetch = async (input, options = {}) => {
  const url = new URL(input)
  if (url.origin !== 'http://localhost:8080') throw Error('Unexpected fixture origin')
  if (url.pathname === '/api/auth/logout') return envelope(null)
  if (url.pathname === '/api/auth/login' || url.pathname === '/api/auth/refresh') {
    if (url.pathname.endsWith('/login')) { sid++; deadline = new Date(Date.now() + 3600000).toISOString() }
    return envelope({ accessToken: `fixture-at-${sid}`, refreshToken: crypto.randomBytes(32).toString('base64url'), tokenType: 'Bearer',
      sessionId: String(sid), sessionExpiresAt: deadline, expiresInSeconds: 600 })
  }
  if (url.pathname === '/api/application') return envelope({ records: [], total: 0, current: 1, size: 10 })
  if (url.pathname === '/v1/web/screenshot-jobs' && options.method === 'POST') {
    const id = new Headers(options.headers).get('Idempotency-Key')
    if (!/^\d{13}\.[0-9a-f-]{36}$/.test(id ?? '')) throw Error('Missing submission identity at Main forwarding boundary')
    c.submissions.push(id)
    if (!jobs.has(id)) jobs.set(id, { jobId: crypto.randomUUID(), status: 'QUEUED', attemptCount: 0, createdAt: '2026-11-19T00:00:00Z', updatedAt: '2026-11-19T00:00:00Z' })
    return new Response(JSON.stringify({ code: 'SUCCESS', data: jobs.get(id) }), { status: 201, headers: { 'Content-Type': 'application/json' } })
  }
  if (/^\/v1\/web\/screenshot-jobs\/[0-9a-f-]{36}$/.test(url.pathname)) {
    const jobId = url.pathname.split('/').at(-1)
    return new Promise(resolve => {
      const row = { jobId, aborted: false, release: () => resolve(envelope({ jobId, status: 'SUCCEEDED', attemptCount: 1,
        createdAt: '2026-11-19T00:00:00Z', updatedAt: '2026-11-19T00:00:00Z', resultSizeBytes: null,
        resultSha256: null, blockedResources: null, resourceLimitReached: null, resultExpiresAt: null,
        failureCode: null, startedAt: null, completedAt: null })) }
      options.signal.addEventListener('abort', () => { row.aborted = true }, { once: true })
      c.polls.push(row)
    })
  }
  if (url.pathname.endsWith('/result')) { c.imageReads++; return new Response(null, { status: 404 }) }
  throw Error('Unmocked fixture route')
}
require('../main.cjs')
