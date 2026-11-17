const { app } = require('electron')
const path = require('node:path')
const directory = process.env.NEXUS_RENEWAL_ACCEPTANCE_DIR
const runtime = path.resolve(__dirname, '../.runtime')
const relative = directory && path.relative(runtime, path.resolve(directory))
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Use test-session-renewal-acceptance.cjs')
const live = process.env.NEXUS_RENEWAL_ACCEPTANCE_LIVE === '1'
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(directory, 'profile') : value)
let lastAt = null, lastRt = null, sid = 0, deadline
const realFetch = globalThis.fetch.bind(globalThis)
const control = globalThis.__renewalAcceptance = {
  requests: [], blockedRendererNetwork: 0, rotations: 0,
  rotationIdentityStable: true, rtChanged: true, dropRefreshOnce: false, dropLogoutOnce: false,
}
app.on('web-contents-created', (_event, contents) => {
  contents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => {
    control.blockedRendererNetwork++
    callback({ cancel: true })
  })
})
globalThis.fetch = async (url, options = {}) => {
  const parsed = new URL(url)
  const method = options.method ?? 'GET'
  const auth = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'].includes(parsed.pathname)
  const allowed = parsed.origin === 'http://localhost:8080' && !parsed.username && !parsed.password &&
    ((auth && method === 'POST' && !parsed.search) || (parsed.pathname === '/api/application' && method === 'GET'))
  if (!allowed) throw Error('Acceptance scope rejected request; no fallback')
  const record = { path: parsed.pathname, method, status: null, injectedLoss: false }
  control.requests.push(record)
  let response
  if (live) response = await realFetch(url, options)
  else if (parsed.pathname === '/api/application') response = Response.json({ code: 'SUCCESS', data: { records: [], total: 0, current: 1, size: 10 } })
  else if (parsed.pathname === '/api/auth/logout') response = Response.json({ code: 'SUCCESS', data: null })
  else {
    if (parsed.pathname === '/api/auth/login') { sid++; deadline = new Date(Date.now() + 120_000).toISOString() }
    response = Response.json({ code: 'SUCCESS', data: { accessToken: `MOCK_AT_${sid}_${control.rotations}`,
      refreshToken: require('node:crypto').randomBytes(32).toString('base64url'), tokenType: 'Bearer',
      expiresInSeconds: 600, sessionId: String(sid), sessionExpiresAt: deadline } })
  }
  record.status = response.status
  if (response.status === 200 && ['/api/auth/login', '/api/auth/refresh'].includes(parsed.pathname)) {
    const envelope = await response.json()
    const data = envelope.data
    if (parsed.pathname === '/api/auth/refresh') {
      control.rotations++
      control.rtChanged &&= data.refreshToken !== lastRt
      control.rotationIdentityStable &&= data.sessionId === String(sid) && data.sessionExpiresAt === deadline
    } else { sid = data.sessionId; deadline = data.sessionExpiresAt }
    lastAt = data.accessToken
    lastRt = data.refreshToken
    // Only the client waiting period is shortened; the actual server JWT is not changed.
    data.expiresInSeconds = 8
    if (parsed.pathname === '/api/auth/refresh') {
      await new Promise(resolve => setTimeout(resolve, 150))
      if (control.dropRefreshOnce) { control.dropRefreshOnce = false; record.injectedLoss = true; throw Error('CONTROLLED_RESPONSE_LOSS') }
    }
    return Response.json(envelope)
  }
  if (parsed.pathname === '/api/auth/logout' && control.dropLogoutOnce) {
    control.dropLogoutOnce = false; record.injectedLoss = true
    // The real server already responded; only the caller's acknowledgement is lost.
    await response.body?.cancel()
    throw Error('CONTROLLED_LOGOUT_ACK_LOSS')
  }
  return response
}
// Test-only private proof checking; no token is returned to the driver or written to evidence.
control.checkRevoked = async () => {
  if (!live) return true
  const response = await realFetch('http://localhost:8080/api/application', { headers: { Authorization: `Bearer ${lastAt}` }, redirect: 'error' })
  const status = response.status
  await response.body?.cancel()
  return status === 401
}
control.cleanupSession = async () => {
  if (!live || !lastAt) return true
  const response = await realFetch('http://localhost:8080/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${lastAt}` }, redirect: 'error' })
  const ok = response.status === 200
  await response.body?.cancel()
  return ok
}
require('../main.cjs')
