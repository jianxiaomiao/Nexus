// Test entry only. Keep the production Main/Preload and session handlers unchanged.
const { app, ipcMain } = require('electron')
const path = require('node:path')

const directory = process.env.NEXUS_INVALIDATION_TEST_DIR
const runtime = path.resolve(__dirname, '../.runtime')
const relative = directory && path.relative(runtime, path.resolve(directory))
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
  throw Error('Use test-session-invalidation-controlled.cjs with an isolated runtime directory')
}

const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(directory, 'profile') : value)

const control = globalThis.__invalidationControlled = {
  requests: [], ipc: [], blockedRendererRequests: 0,
  mode: 'success', ttlSeconds: 600, loginResponses: 0,
  loseNextClearAck: false, release401: null,
}

// Prevent Renderer HTTP/HTTPS traffic as well as replacing all Main fetch calls.
app.on('web-contents-created', (_event, contents) => {
  contents.session.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (_details, callback) => {
    control.blockedRendererRequests++
    callback({ cancel: true })
  })
})

const realHandle = ipcMain.handle.bind(ipcMain)
ipcMain.handle = (channel, listener) => realHandle(channel, async (event, ...args) => {
  const result = await listener(event, ...args)
  if (['auth-session:read', 'auth-session:write', 'auth-session:clear'].includes(channel)) {
    const record = {
      channel, status: result?.status ?? null, code: result?.code ?? null,
      expectedRevision: channel === 'auth-session:read' ? null : args[0]?.expectedRevision ?? null,
      revision: result?.authState?.revision ?? null,
      hasSession: result?.authState ? result.authState.session !== null : null,
      lostAcknowledgement: false,
    }
    control.ipc.push(record)
    if (channel === 'auth-session:clear' && result?.status === 'ok' && control.loseNextClearAck) {
      control.loseNextClearAck = false
      record.lostAcknowledgement = true
      // The actual handler already ran. Only its acknowledgement is lost.
      throw Error('CONTROLLED_CLEAR_ACKNOWLEDGEMENT_LOSS')
    }
  }
  return result
})

const timestamp = '2026-11-15T00:00:00Z'
const application = {
  id: 1, name: '受控会话验收应用', status: 0, createdAt: timestamp, updatedAt: timestamp,
  quota: { periodStart: timestamp, periodEnd: '2030-01-01T00:00:00Z', quotaLimit: 1000, usedCount: 0, restCount: 1000 },
}

// No real fetch fallback, socket, mock HTTP server or backend port is used.
globalThis.fetch = async (url, options = {}) => {
  const parsed = new URL(url)
  const method = options.method ?? 'GET'
  const headers = new Headers(options.headers)
  const allowed = parsed.origin === 'http://localhost:8080' && !parsed.username && !parsed.password && (
    (method === 'POST' && parsed.pathname === '/api/auth/login' && !parsed.search) ||
    (method === 'GET' && parsed.pathname === '/api/application' &&
      [...parsed.searchParams.keys()].every(key => ['current', 'size'].includes(key)))
  )
  const record = {
    path: allowed ? parsed.pathname : '<blocked>', method, allowed,
    hasBearer: headers.get('Authorization')?.startsWith('Bearer ') ?? false,
    hasContextHeader: [...headers.keys()].some(name => /revision|auth.?context|operationversion/i.test(name)),
    mode: control.mode, status: null,
  }
  control.requests.push(record)
  if (!allowed) throw Error('Request outside controlled session test scope')
  if (parsed.pathname === '/api/auth/login') {
    control.loginResponses++
    record.status = 200
    return Response.json({ code: 'SUCCESS', message: 'Controlled login', data: {
      accessToken: `CONTROLLED_TEST_ONLY_${control.loginResponses}`,
      tokenType: 'Bearer', expiresInSeconds: control.ttlSeconds,
    } })
  }
  if (!record.hasBearer) throw Error('Controlled management query must have Bearer')
  if (control.mode === 'deferred401') {
    if (control.release401) throw Error('Only one deferred query is allowed')
    await new Promise((resolve, reject) => {
      const onAbort = () => { control.release401 = null; reject(Error('Controlled request aborted')) }
      options.signal?.addEventListener('abort', onAbort, { once: true })
      control.release401 = () => {
        control.release401 = null
        options.signal?.removeEventListener('abort', onAbort)
        resolve()
      }
    })
    record.status = 401
    return Response.json({ code: 'INVALID_ACCESS_TOKEN', message: 'Controlled old 401' }, { status: 401 })
  }
  if (control.mode === 'unauthorized') {
    record.status = 401
    return Response.json({ code: 'INVALID_ACCESS_TOKEN', message: 'Controlled current 401' }, { status: 401 })
  }
  record.status = 200
  return Response.json({ code: 'SUCCESS', message: 'Controlled list', data: {
    records: [application], total: 1, current: 1, size: 10,
  } })
}

require('../main.cjs')
