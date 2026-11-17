// Private Main memory. Tokens leave this module only as an allowlisted AT snapshot.
function createAuthSession({ fetch: fetchBackend, now = Date.now, timeoutMs = 8000, responseLimit = 1024 * 1024 }) {
  let state = { revision: 0, session: null }
  let loginVersion = 0
  let loginPending = null
  let refreshTask = null
  let lastRefresh = null
  let logoutTask = null
  let revocationNotice = null
  const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  const revision = value => Number.isSafeInteger(value) && value >= 0
  const failed = (code, httpStatus = 503) => ({ status: 'failed', code, httpStatus })
  const validInput = input => record(input) && revision(input.expectedRevision)

  function snapshot() {
    const session = state.session
    return { revision: state.revision, session: session === null ? null : {
      accessToken: session.accessToken, tokenType: 'Bearer', expiresAt: session.expiresAt,
      sessionId: session.sessionId, sessionExpiresAt: session.sessionExpiresAt,
    } }
  }

  function erase() {
    loginVersion++
    lastRefresh = null
    state = { revision: state.revision + 1, session: null }
    return snapshot()
  }

  function read() {
    if (state.session !== null && state.session.sessionExpiresAt <= now()) erase()
    return { status: 'ok', authState: snapshot(), ...(revocationNotice ? { serverRevocation: revocationNotice } : {}) }
  }

  // An acknowledgement may still be in flight after this exact session rotated.
  // A different login intent/session can never satisfy this exception.
  function matchesRotation(expectedRevision) {
    return lastRefresh !== null && lastRefresh.from === expectedRevision &&
      lastRefresh.to === state.revision && lastRefresh.version === loginVersion &&
      lastRefresh.sessionId === state.session?.sessionId
  }

  function clear(input) {
    if (!validInput(input)) return failed('INVALID_REQUEST', 400)
    if (input.expectedRevision !== state.revision && !matchesRotation(input.expectedRevision)) return failed('SESSION_CHANGED', 409)
    return { status: 'ok', code: 'SUCCESS', authState: erase() }
  }

  async function requestAuth(path, { body, accessToken } = {}) {
    const controller = new AbortController()
    let reader, timer
    const timeout = new Promise(resolve => {
      timer = setTimeout(() => {
        controller.abort()
        if (reader) Promise.resolve(reader.cancel()).catch(() => {})
        resolve(failed('TIMEOUT', 504))
      }, timeoutMs)
    })
    const request = async () => {
      try {
        const headers = { Accept: 'application/json' }
        if (body) headers['Content-Type'] = 'application/json'
        if (accessToken) headers.Authorization = `Bearer ${accessToken}`
        const response = await fetchBackend(`http://localhost:8080/api/auth/${path}`, {
          method: 'POST', headers, body: body ? JSON.stringify(body) : undefined,
          redirect: 'error', signal: controller.signal,
        })
        if (controller.signal.aborted) return failed('TIMEOUT', 504)
        if (response.status !== 200) {
          if (response.body) Promise.resolve(response.body.cancel()).catch(() => {})
          const code = ({ 400: 'INVALID_REQUEST', 401: path === 'login' ? 'AUTH_INVALID_CREDENTIALS' : 'INVALID_REFRESH_TOKEN',
            403: 'AUTH_ACCOUNT_FORBIDDEN' })[response.status] ?? 'AUTH_SESSION_UNAVAILABLE'
          return failed(code, [400, 401, 403].includes(response.status) ? response.status : 503)
        }
        if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('application/json') || !response.body) {
          return failed('INVALID_RESPONSE', 502)
        }
        reader = response.body.getReader()
        const chunks = []
        let length = 0
        while (true) {
          const { done, value } = await reader.read()
          if (controller.signal.aborted) return failed('TIMEOUT', 504)
          if (done) break
          length += value.byteLength
          if (length > responseLimit) return failed('INVALID_RESPONSE', 502)
          chunks.push(value)
        }
        const envelope = JSON.parse(Buffer.concat(chunks, length).toString('utf8'))
        if (envelope?.code !== 'SUCCESS') return failed('INVALID_RESPONSE', 502)
        return { status: 'ok', data: envelope.data }
      } catch {
        return failed(controller.signal.aborted ? 'TIMEOUT' : 'UPSTREAM_UNAVAILABLE', controller.signal.aborted ? 504 : 502)
      } finally {
        if (reader) Promise.resolve(reader.cancel()).catch(() => {})
      }
    }
    try { return await Promise.race([request(), timeout]) }
    finally { clearTimeout(timer); controller.abort() }
  }

  function decodeSession(data) {
    const receivedAt = now()
    const deadline = typeof data?.sessionExpiresAt === 'string' ? Date.parse(data.sessionExpiresAt) : NaN
    if (!record(data) || typeof data.accessToken !== 'string' || !/^\S{1,4096}$/.test(data.accessToken) ||
        data.tokenType !== 'Bearer' || !Number.isSafeInteger(data.expiresInSeconds) || data.expiresInSeconds <= 0 ||
        typeof data.refreshToken !== 'string' || !/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(data.refreshToken) ||
        typeof data.sessionId !== 'string' || !/^[1-9][0-9]{0,18}$/.test(data.sessionId) ||
        !Number.isFinite(deadline) || deadline <= receivedAt) return null
    const expiresAt = Math.min(receivedAt + data.expiresInSeconds * 1000, deadline)
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= receivedAt) return null
    return { accessToken: data.accessToken, refreshToken: data.refreshToken, tokenType: 'Bearer',
      expiresAt, sessionId: data.sessionId, sessionExpiresAt: deadline }
  }

  async function login(input) {
    if (!validInput(input) || typeof input.email !== 'string' || input.email.length === 0 || input.email.length > 254 ||
        typeof input.password !== 'string' || input.password.length === 0 || input.password.length > 64) return failed('INVALID_REQUEST', 400)
    if (input.expectedRevision !== state.revision) return failed('SESSION_CHANGED', 409)
    const { expectedRevision, email, password } = input
    const version = ++loginVersion
    loginPending = version
    lastRefresh = null
    logoutTask = null
    try {
      const result = await requestAuth('login', { body: { email, password } })
      if (version !== loginVersion || expectedRevision !== state.revision) return failed('SESSION_CHANGED', 409)
      if (result.status !== 'ok') return result
      const session = decodeSession(result.data)
      if (!session) return failed('INVALID_RESPONSE', 502)
      state = { revision: state.revision + 1, session }
      revocationNotice = null
      return { status: 'ok', code: 'SUCCESS', authState: snapshot() }
    } finally { if (loginPending === version) loginPending = null }
  }

  function ensure(input) {
    if (!validInput(input) || (input.forceRefresh !== undefined && typeof input.forceRefresh !== 'boolean')) {
      return Promise.resolve(failed('INVALID_REQUEST', 400))
    }
    if (loginPending === loginVersion) return Promise.resolve(failed('SESSION_CHANGED', 409))
    if (state.session === null && input.expectedRevision <= state.revision) {
      return Promise.resolve({ status: 'reauthenticate', code: 'SESSION_ENDED', authState: snapshot() })
    }
    if (matchesRotation(input.expectedRevision)) return Promise.resolve({ status: 'ok', authState: snapshot() })
    if (input.expectedRevision !== state.revision) return Promise.resolve(failed('SESSION_CHANGED', 409))
    if (refreshTask?.revision === state.revision && refreshTask.version === loginVersion) return refreshTask.promise
    if (state.session === null) return Promise.resolve({ status: 'reauthenticate', code: 'SESSION_ENDED', authState: snapshot() })
    if (state.session.sessionExpiresAt <= now()) {
      return Promise.resolve({ status: 'reauthenticate', code: 'SESSION_ENDED', authState: erase() })
    }
    if (!input.forceRefresh && state.session.expiresAt > now()) return Promise.resolve({ status: 'ok', authState: snapshot() })
    const original = state.session
    const from = state.revision
    const version = loginVersion
    const current = () => state.revision === from && loginVersion === version && state.session === original
    const task = { revision: from, version, promise: null }
    task.promise = (async () => {
      // Never retry an RT: a missing response can mean that its rotation already committed.
      const result = await requestAuth('refresh', { body: { refreshToken: original.refreshToken } })
      if (!current()) return failed('SESSION_CHANGED', 409)
      const session = result.status === 'ok' ? decodeSession(result.data) : null
      if (!session || session.sessionId !== original.sessionId || session.sessionExpiresAt !== original.sessionExpiresAt ||
          session.refreshToken === original.refreshToken) {
        return { status: 'reauthenticate', code: result.status === 'ok' ? 'INVALID_RESPONSE' : result.code, authState: erase() }
      }
      state = { revision: from + 1, session }
      lastRefresh = { from, to: state.revision, version, sessionId: session.sessionId }
      return { status: 'ok', authState: snapshot() }
    })().finally(() => { if (refreshTask === task) refreshTask = null })
    refreshTask = task
    return task.promise
  }

  function logout(input) {
    if (!validInput(input)) return Promise.resolve(failed('INVALID_REQUEST', 400))
    if (logoutTask?.expectedRevision === input.expectedRevision && state.session === null &&
        state.revision === logoutTask.emptyRevision) return logoutTask.promise
    if (input.expectedRevision !== state.revision && !matchesRotation(input.expectedRevision)) {
      return Promise.resolve(failed('SESSION_CHANGED', 409))
    }
    const accessToken = state.session?.accessToken
    // Capture the original proof, erase locally first, and never refresh in order to log out.
    const emptyState = erase()
    revocationNotice = accessToken ? 'pending' : revocationNotice
    const task = { expectedRevision: input.expectedRevision, emptyRevision: emptyState.revision, promise: null }
    task.promise = (async () => {
      const result = accessToken ? await requestAuth('logout', { accessToken }) : null
      const serverRevocation = !accessToken ? 'not-needed' : result.status === 'ok' && result.data === null ? 'confirmed' : 'unconfirmed'
      if (accessToken && state.session === null && state.revision === emptyState.revision) {
        revocationNotice = serverRevocation === 'confirmed' ? null : 'unconfirmed'
      }
      return { status: 'ok', authState: emptyState, serverRevocation }
    })()
    logoutTask = task
    return task.promise
  }

  return { read, clear, login, ensure, logout }
}

function isAuthPath(pathname) {
  try {
    const decoded = decodeURIComponent(pathname).replace(/;[^/]*/g, '').replace(/\/+/g, '/')
    return decoded === '/api/auth' || decoded.startsWith('/api/auth/')
  } catch { return true }
}
module.exports = { createAuthSession, isAuthPath }
