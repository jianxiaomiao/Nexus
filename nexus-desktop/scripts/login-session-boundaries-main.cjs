const { app } = require('electron')
const path = require('node:path')
const directory = process.env.NEXUS_LOGIN_SESSION_TEST_DIR
const runtime = path.resolve(__dirname, '../.runtime')
const relative = directory && path.relative(runtime, path.resolve(directory))
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Use test-login-session-boundaries.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(directory, 'profile') : value)
const control = globalThis.__loginSessionBoundary = { requests: 0, blockedNetwork: 0 }
app.on('web-contents-created', (_event, contents) => {
  contents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => {
    control.blockedNetwork++
    callback({ cancel: true })
  })
})
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  control.requests++
  if (parsed.href !== 'http://localhost:8080/api/auth/login' || options.method !== 'POST') {
    throw Error('No real network fallback')
  }
  return Response.json({ code: 'SUCCESS', data: {
    accessToken: 'CONTROLLED_AT_ONLY', tokenType: 'Bearer', expiresInSeconds: 600,
    refreshToken: 'A'.repeat(43), sessionId: '7',
    sessionExpiresAt: new Date(Date.now() + 86_400_000).toISOString(),
    unexpected: { refreshToken: 'A'.repeat(43) },
  } })
}
require('../main.cjs')
