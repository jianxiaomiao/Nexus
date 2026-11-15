const { app, ipcMain } = require('electron')
const path = require('node:path')
const dir = process.env.NEXUS_SESSION_ACCEPTANCE_DIR
if (!dir) throw Error('Use test-session-refresh-acceptance.cjs')
const runtime = path.resolve(__dirname, '../.runtime')
const relative = path.relative(runtime, path.resolve(dir))
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Invalid acceptance directory')

const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)

// Observe results only. Never retain invocation arguments, tokens or response bodies.
const control = globalThis.__sessionAcceptance = { requests: [], ipc: [] }
const handle = ipcMain.handle.bind(ipcMain)
ipcMain.handle = (channel, listener) => {
  if (!['auth-session:read', 'auth-session:write', 'auth-session:clear'].includes(channel)) {
    return handle(channel, listener)
  }
  return handle(channel, async (event, ...args) => {
    const result = await listener(event, ...args)
    control.ipc.push({
      channel, status: result?.status ?? null, code: result?.code ?? null,
      revision: result?.authState?.revision ?? null,
      hasSession: result?.authState ? result.authState.session !== null : null,
      expiresAt: result?.authState?.session?.expiresAt ?? null,
    })
    return result
  })
}

const realFetch = globalThis.fetch.bind(globalThis)
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  const method = options?.method ?? 'GET'
  const allowed = parsed.origin === 'http://localhost:8080' && (
    (parsed.pathname === '/api/auth/login' && method === 'POST') ||
    (parsed.pathname === '/api/application' && method === 'GET')
  )
  const record = {
    path: allowed ? parsed.pathname : '<blocked>', method,
    hasBearer: new Headers(options?.headers).get('Authorization')?.startsWith('Bearer ') ?? false,
    status: null, allowed,
  }
  control.requests.push(record)
  // This fixture cannot create screenshot jobs or any other business resource.
  if (!allowed) throw Error('Request outside session acceptance scope')
  const response = await realFetch(url, options)
  record.status = response.status
  return response
}

require('../main.cjs')
