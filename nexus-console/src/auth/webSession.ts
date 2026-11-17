/** Web-only credential transport and same-origin coordination. No token is persisted or broadcast. */
export interface WebTokens {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  expiresAt: number
  sessionId: string
  sessionExpiresAt: number
  webGeneration: string
}
export interface SharedWebState {
  generation: string
  operationId: string
  phase: 'ready' | 'working' | 'logged-out' | 'uncertain'
  operation: 'login' | 'refresh' | 'logout'
  sessionId: string | null
  sessionExpiresAt: number | null
  revocation?: 'pending' | 'confirmed' | 'unconfirmed'
}
export type WebResult =
  | { status: 'ok'; tokens: WebTokens }
  | { status: 'anonymous' | 'uncertain' | 'stale'; notice?: string }

export class WebSessionError extends Error {
  readonly status: number
  readonly code: string
  constructor(status: number, code: string) {
    super('Web session operation failed')
    this.status = status
    this.code = code
  }
}
export const WEB_STATE_KEY = 'nexus.auth.web.v1'
const LOCK = 'nexus.auth.web.credentials.v1'
const CHANNEL = 'nexus.auth.web.events.v1'
const HEADERS = { 'Content-Type': 'application/json', 'X-Nexus-Session-Transport': 'cookie' }

export function isWebBrowser(): boolean {
  return typeof window !== 'undefined' && typeof document !== 'undefined' && !window.nexusDesktop &&
    (location.protocol === 'http:' || location.protocol === 'https:')
}

export function createWebSessionCoordinator() {
  if (!navigator.locks || typeof BroadcastChannel === 'undefined') throw new WebSessionError(503, 'UNSUPPORTED_WEB_SESSION')
  const listeners = new Set<(state: SharedWebState) => void>()
  const channel = new BroadcastChannel(CHANNEL)
  let lastEvent = ''
  const fresh = () => crypto.randomUUID()
  function read(): SharedWebState | null {
    const raw = localStorage.getItem(WEB_STATE_KEY)
    if (raw === null) return null
    let value: SharedWebState
    try { value = JSON.parse(raw) } catch { throw new WebSessionError(503, 'INVALID_SHARED_STATE') }
    if (!value || typeof value.generation !== 'string' || typeof value.operationId !== 'string' ||
        !['ready', 'working', 'logged-out', 'uncertain'].includes(value.phase) ||
        !['login', 'refresh', 'logout'].includes(value.operation) ||
        !(value.sessionId === null || typeof value.sessionId === 'string') ||
        !(value.sessionExpiresAt === null || Number.isFinite(value.sessionExpiresAt))) {
      throw new WebSessionError(503, 'INVALID_SHARED_STATE')
    }
    return value
  }
  // Probe availability before sending a request that could rotate the only usable RT.
  const probe = `${WEB_STATE_KEY}.probe.${fresh()}`
  localStorage.setItem(probe, '1'); localStorage.removeItem(probe)
  function publish(value: SharedWebState) {
    // Explicit projection also prevents accidental token persistence after future refactors.
    const safe = { generation: value.generation, operationId: value.operationId, phase: value.phase,
      operation: value.operation, sessionId: value.sessionId, sessionExpiresAt: value.sessionExpiresAt,
      ...(value.revocation ? { revocation: value.revocation } : {}) }
    localStorage.setItem(WEB_STATE_KEY, JSON.stringify(safe))
    channel.postMessage({ changed: true })
  }
  function receive() {
    try {
      const value = read()
      if (!value) return
      const id = `${value.operationId}:${value.phase}:${value.revocation ?? ''}`
      if (id === lastEvent) return
      lastEvent = id
      listeners.forEach(listener => listener(value))
    } catch {
      const invalid: SharedWebState = { generation: fresh(), operationId: fresh(), phase: 'uncertain',
        operation: 'refresh', sessionId: null, sessionExpiresAt: null }
      listeners.forEach(listener => listener(invalid))
    }
  }
  channel.onmessage = receive
  const storageListener = (event: StorageEvent) => { if (event.key === WEB_STATE_KEY || event.key === null) receive() }
  window.addEventListener('storage', storageListener)

  async function locked<T>(action: () => Promise<T>): Promise<T> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 10_000)
    try {
      return await navigator.locks.request(LOCK, { signal: controller.signal }, async () => {
        clearTimeout(timer)
        return action()
      })
    } catch (error) {
      if (controller.signal.aborted) throw new WebSessionError(503, 'SESSION_COORDINATION_BUSY')
      throw error
    } finally { clearTimeout(timer) }
  }

  async function request(path: 'login' | 'refresh' | 'logout', body: object): Promise<unknown> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 8000)
    try {
      const response = await fetch(`/api/auth/${path}`, { method: 'POST', headers: HEADERS,
        credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal,
        body: JSON.stringify(body) })
      if (!response.ok) {
        const code = response.status === 401 ? (path === 'login' ? 'AUTH_INVALID_CREDENTIALS' : 'INVALID_REFRESH_TOKEN')
          : response.status === 403 ? 'AUTH_ACCOUNT_FORBIDDEN' : response.status === 400 ? 'INVALID_REQUEST' : 'AUTH_SESSION_UNAVAILABLE'
        await response.body?.cancel()
        throw new WebSessionError(response.status, code)
      }
      if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('application/json') || !response.body) throw new WebSessionError(502, 'INVALID_RESPONSE')
      const reader = response.body.getReader()
      const chunks: Uint8Array[] = []
      let size = 0
      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          size += value.byteLength
          if (size > 64 * 1024) throw new WebSessionError(502, 'INVALID_RESPONSE')
          chunks.push(value)
        }
      } finally { void reader.cancel().catch(() => {}) }
      const bytes = new Uint8Array(size)
      let offset = 0
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
      const envelope = JSON.parse(new TextDecoder().decode(bytes))
      if (envelope?.code !== 'SUCCESS') throw new WebSessionError(502, 'INVALID_RESPONSE')
      return envelope.data
    } catch (error) {
      if (error instanceof WebSessionError) throw error
      throw new WebSessionError(503, 'AUTH_SESSION_UNAVAILABLE')
    } finally { clearTimeout(timer); controller.abort() }
  }

  function parseTokens(raw: unknown, shared: SharedWebState): WebTokens {
    const data = raw as Record<string, unknown> | null
    const deadline = typeof data?.sessionExpiresAt === 'string' ? Date.parse(data.sessionExpiresAt) : NaN
    if (!data || Object.hasOwn(data, 'refreshToken') || typeof data.accessToken !== 'string' || !/^\S{1,4096}$/.test(data.accessToken) ||
        data.tokenType !== 'Bearer' || !Number.isSafeInteger(data.expiresInSeconds) || Number(data.expiresInSeconds) <= 0 ||
        typeof data.sessionId !== 'string' || !/^[1-9][0-9]{0,18}$/.test(data.sessionId) || !Number.isFinite(deadline) || deadline <= Date.now() ||
        (shared.operation === 'refresh' && shared.sessionId !== null && shared.sessionId !== data.sessionId) ||
        (shared.operation === 'refresh' && shared.sessionExpiresAt !== null && shared.sessionExpiresAt !== deadline)) {
      throw new WebSessionError(502, 'INVALID_RESPONSE')
    }
    return { accessToken: data.accessToken, tokenType: 'Bearer', expiresInSeconds: Number(data.expiresInSeconds),
      expiresAt: Math.min(Date.now() + Number(data.expiresInSeconds) * 1000, deadline), sessionId: data.sessionId,
      sessionExpiresAt: deadline, webGeneration: shared.generation }
  }

  const current = (value: SharedWebState) => read()?.operationId === value.operationId
  function stopped(state: SharedWebState | null): WebResult | null {
    if (state?.phase === 'logged-out') return { status: 'anonymous',
      ...(state.revocation === 'pending' || state.revocation === 'unconfirmed' ? { notice: '已退出本地登录，但服务端撤销未确认。' } : {}) }
    if (state?.phase === 'uncertain' || state?.phase === 'working') return { status: 'uncertain', notice: '上次会话操作结果无法确认，请重新登录。' }
    return null
  }

  async function login(credentials: { email: string; password: string }, isCurrent = () => true): Promise<WebResult> {
    return locked(async () => {
      if (!isCurrent()) return { status: 'stale' }
      const before = read()
      const working: SharedWebState = { generation: fresh(), operationId: fresh(), phase: 'working', operation: 'login', sessionId: null, sessionExpiresAt: null,
        ...(before?.revocation === 'pending' || before?.revocation === 'unconfirmed' ? { revocation: 'unconfirmed' as const } : {}) }
      publish(working)
      try {
        const data = await request('login', credentials)
        if (!current(working)) return { status: 'stale' }
        const tokens = parseTokens(data, working)
        if (!isCurrent()) {
          // This exact login already wrote a Cookie. Revoke it before releasing the credential lock.
          let confirmed = false
          try {
            const result = await request('logout', { expectedSessionId: tokens.sessionId }) as { serverRevocation?: string }
            confirmed = result?.serverRevocation === 'confirmed'
          } catch {}
          // A newer logout intent may have arrived while compensation was in flight.
          if (current(working)) publish({ ...working, phase: 'logged-out', sessionId: tokens.sessionId,
            revocation: confirmed ? 'confirmed' : 'unconfirmed' })
          return { status: 'stale' }
        }
        publish({ ...working, phase: 'ready', sessionId: tokens.sessionId, sessionExpiresAt: tokens.sessionExpiresAt, revocation: undefined })
        return { status: 'ok', tokens }
      } catch (error) {
        if (current(working)) publish({ ...working, phase: error instanceof WebSessionError && error.status < 500 ? 'logged-out' : 'uncertain' })
        throw error
      }
    })
  }

  async function restore(expectedGeneration?: string): Promise<WebResult> {
    return locked(async () => {
      const before = read()
      if (expectedGeneration && before && before.generation !== expectedGeneration) return { status: 'stale' }
      const stop = stopped(before)
      if (stop) {
        if (before?.phase === 'working') publish({ ...before, phase: 'uncertain' })
        return stop
      }
      const working: SharedWebState = { generation: before?.generation ?? fresh(), operationId: fresh(),
        phase: 'working', operation: 'refresh', sessionId: before?.sessionId ?? null, sessionExpiresAt: before?.sessionExpiresAt ?? null }
      publish(working)
      try {
        const data = await request('refresh', working.sessionId ? { expectedSessionId: working.sessionId } : {})
        if (!current(working)) return { status: 'stale' }
        const tokens = parseTokens(data, working)
        publish({ ...working, phase: 'ready', sessionId: tokens.sessionId, sessionExpiresAt: tokens.sessionExpiresAt })
        return { status: 'ok', tokens }
      } catch (error) {
        if (!current(working)) return { status: 'stale' }
        const anonymous = error instanceof WebSessionError && error.status === 401
        publish({ ...working, phase: anonymous ? 'logged-out' : 'uncertain' })
        return { status: anonymous ? 'anonymous' : 'uncertain', notice: anonymous ? undefined : '续期结果无法确认，请重新登录。' }
      }
    })
  }

  async function logout(expectedGeneration: string, expectedSessionId: string): Promise<{ status: 'ok' | 'stale'; unconfirmed?: boolean }> {
    const before = read()
    if (expectedGeneration && before && before.generation !== expectedGeneration) return { status: 'stale' }
    // Intent is durable before waiting for the lock. In-flight responses must not restore it.
    const intent: SharedWebState = { generation: fresh(), operationId: fresh(), phase: 'logged-out', operation: 'logout',
      sessionId: expectedSessionId || before?.sessionId || null, sessionExpiresAt: before?.sessionExpiresAt ?? null, revocation: 'pending' }
    publish(intent)
    try {
      return await locked(async () => {
        if (!current(intent)) return { status: 'stale' }
        let unconfirmed = true
        try {
          const data = await request('logout', intent.sessionId ? { expectedSessionId: intent.sessionId } : {}) as { serverRevocation?: string }
          unconfirmed = data?.serverRevocation !== 'confirmed' && (data?.serverRevocation !== 'not-needed' || before?.revocation === 'unconfirmed' || before?.revocation === 'pending')
        } catch { /* Local logout remains in force even when the Cookie response is lost. */ }
        if (!current(intent)) return { status: 'stale' }
        publish({ ...intent, revocation: unconfirmed ? 'unconfirmed' : 'confirmed' })
        return { status: 'ok', unconfirmed }
      })
    } catch {
      if (current(intent)) publish({ ...intent, revocation: 'unconfirmed' })
      return { status: 'ok', unconfirmed: true }
    }
  }

  return { login, restore, logout, read,
    subscribe(listener: (state: SharedWebState) => void) { listeners.add(listener); return () => listeners.delete(listener) },
    dispose() { channel.close(); window.removeEventListener('storage', storageListener); listeners.clear() } }
}
let instance: ReturnType<typeof createWebSessionCoordinator> | undefined
export function webSession() { return instance ??= createWebSessionCoordinator() }
