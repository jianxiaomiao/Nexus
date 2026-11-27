import { defineStore } from 'pinia'
import { toRaw } from 'vue'
import type { AuthMeResponse, LoginResponse } from "@/api/auth"
import type { AuthState } from '@/types/desktop'
import { isWebBrowser, webSession, type WebTokens, type SharedWebState } from '../auth/webSession.ts'

export type SessionReadyResult =
  | { status: 'ok' }
  | { status: 'stale' }
  | { status: 'reauthenticate'; operationVersion: number }
  | { status: 'failed' }

type InitializationStatus = 'idle' | 'loading' | 'ready' | 'failed'

export interface LoginAttempt {
  operationVersion: number
  mainRevision: number | null
  blocked?: true
}

export type SessionContext = Pick<LoginAttempt, 'operationVersion' | 'mainRevision'> & { webRevision?: number }

export type LoginCompletionResult =
  | { status: 'ok'; operationVersion: number }
  | { status: 'stale' }
  | { status: 'failed'; code: 'NOT_READY' | 'INVALID_RESPONSE' | 'SESSION_CHANGED' | 'SAVE_FAILED' | 'CLEAR_PENDING' }

export type InvalidateSessionInput =
  | { reason: 'logout' }
  | { reason: 'expired' | 'unauthorized'; context: SessionContext }

export type InvalidateSessionResult =
  | { status: 'ok'; operationVersion: number }
  | { status: 'stale' }
  | { status: 'failed'; code: 'NOT_READY' | 'SESSION_CHANGED' | 'CLEAR_FAILED' }

const clearingTasks = new WeakMap<object, {
  targetVersion: number
  targetRevision: number | null
  operationVersion: number
  promise: Promise<InvalidateSessionResult>
}>()

// Keep pending work outside reactive state and separate for each Pinia instance.
const initializationTasks = new WeakMap<object, Promise<void>>()
const renewalTasks = new WeakMap<object, { version: number; revision: number | null; promise: Promise<SessionReadyResult> }>()
const webSubscriptions = new WeakSet<object>()

function isAuthState(state: AuthState): boolean {
  const session = state?.session
  return Number.isSafeInteger(state?.revision) && state.revision >= 0 && (
    session === null || (
      !!session && typeof session === 'object' && !Array.isArray(session) &&
      typeof session.accessToken === 'string' && session.accessToken.length > 0 &&
      session.accessToken.length <= 4096 && session.tokenType === 'Bearer' &&
      Number.isFinite(session.expiresAt) &&
      typeof session.sessionId === 'string' && /^[1-9][0-9]{0,18}$/.test(session.sessionId) &&
      Number.isFinite(session.sessionExpiresAt) && session.expiresAt <= session.sessionExpiresAt
    )
  )
}

type UserSession = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  expiresAt: number // 绝对过期时间戳（毫秒）
  sessionId: string
  sessionExpiresAt: number
  mainRevision: number | null
  operationVersion: number
  initializationStatus: InitializationStatus
  initializationError: string | null
  clearing: boolean
  clearError: string | null
  sessionNotice: string | null
  webGeneration: string
  webRevision: number
  webEvent: number
  profile: AuthMeResponse | null
  profileSessionId: string
}

export const useUserStore = defineStore('userStore', {
  state: (): UserSession => ({
    accessToken: "",
    tokenType: "",
    expiresInSeconds: 0,
    expiresAt: 0,
    sessionId: '',
    sessionExpiresAt: 0,
    mainRevision: null,
    operationVersion: 0,
    initializationStatus: 'idle',
    initializationError: null,
    clearing: false,
    clearError: null,
    sessionNotice: null,
    webGeneration: '',
    webRevision: 0,
    webEvent: 0,
    profile: null,
    profileSessionId: '',
  }),

  // 删掉那些单纯返回state的getter，直接 store.xxx 访问即可

  actions: {
    sessionContext(): SessionContext {
      return { operationVersion: this.operationVersion, mainRevision: this.mainRevision, webRevision: this.webRevision }
    },

    applyWebTokens(tokens: WebTokens) {
      // The lock's Promise may resolve before a delayed cross-tab event is delivered.
      const shared = webSession().read()
      if (shared?.generation !== tokens.webGeneration || !(shared.phase === 'ready' ||
          (shared.phase === 'working' && shared.operation === 'refresh'))) {
        this.checkWebState()
        return false
      }
      this.accessToken = tokens.accessToken
      this.tokenType = tokens.tokenType
      this.expiresAt = tokens.expiresAt
      this.expiresInSeconds = tokens.expiresInSeconds
      this.sessionId = tokens.sessionId
      this.sessionExpiresAt = tokens.sessionExpiresAt
      this.webGeneration = tokens.webGeneration
      this.webRevision++
      this.sessionNotice = null
      return true
    },

    observeWebState(state: SharedWebState) {
      if (state.generation === this.webGeneration && ((state.phase === 'ready' && !!this.accessToken) ||
          (state.phase === 'working' && state.operation === 'refresh'))) return
      this.clearSession()
      this.webGeneration = state.generation
      this.sessionNotice = state.phase === 'uncertain' ? '会话结果无法确认，请重新登录。'
        : state.revocation === 'unconfirmed' || state.revocation === 'pending' ? '已退出本地登录，但服务端撤销未确认。' : null
      this.webEvent++
    },

    /** Synchronous gate also covers delayed/lost BroadcastChannel events. */
    checkWebState(): boolean {
      if (!isWebBrowser()) return true
      try {
        const state = webSession().read()
        if (!state || state.generation !== this.webGeneration || state.phase === 'logged-out' || state.phase === 'uncertain' ||
            (state.phase === 'working' && state.operation === 'login')) {
          if (state) this.observeWebState(state)
          else this.clearSession()
          return false
        }
        return true
      } catch {
        this.clearSession()
        this.sessionNotice = '无法协调网页会话，请重新登录。'
        return false
      }
    },

    async restoreWebSession(): Promise<SessionReadyResult> {
      const version = this.operationVersion
      try {
        const result = await webSession().restore(this.webGeneration || undefined)
        if (version !== this.operationVersion || this.clearing) return { status: 'stale' }
        if (result.status === 'ok') {
          return this.applyWebTokens(result.tokens) ? { status: 'ok' } : { status: 'stale' }
        }
        if (result.status === 'stale') return { status: 'stale' }
        this.clearSession()
        this.sessionNotice = result.notice ?? null
        return { status: 'reauthenticate', operationVersion: this.operationVersion }
      } catch {
        if (version !== this.operationVersion) return { status: 'stale' }
        this.clearSession()
        this.sessionNotice = '无法协调网页会话，请重新登录。'
        return { status: 'reauthenticate', operationVersion: this.operationVersion }
      }
    },

    /** Wait for Main's one rotation. No RT or business-request replay crosses this boundary. */
    ensureSession(context: SessionContext, forceRefresh = false): Promise<SessionReadyResult> {
      if (context.operationVersion !== this.operationVersion || this.clearing || this.clearError) {
        return Promise.resolve({ status: 'stale' })
      }
      if (isWebBrowser()) {
        if (context.webRevision !== undefined && context.webRevision !== this.webRevision) {
          return Promise.resolve(this.accessToken && this.expiresAt > Date.now() ? { status: 'ok' } : { status: 'stale' })
        }
        const owner = toRaw(this)
        const pending = renewalTasks.get(owner)
        if (pending?.version === context.operationVersion) return pending.promise
        if (!this.checkWebState()) return Promise.resolve({ status: 'reauthenticate', operationVersion: this.operationVersion })
        if (!forceRefresh && this.accessToken && this.expiresAt > Date.now()) return Promise.resolve({ status: 'ok' })
        const task = { version: context.operationVersion, revision: null, promise: Promise.resolve<SessionReadyResult>({ status: 'failed' }) }
        task.promise = this.restoreWebSession().finally(() => { if (renewalTasks.get(owner) === task) renewalTasks.delete(owner) })
        renewalTasks.set(owner, task)
        return task.promise
      }
      if (context.mainRevision !== this.mainRevision) {
        return Promise.resolve(context.mainRevision !== null && this.mainRevision !== null &&
          this.mainRevision > context.mainRevision && this.accessToken && this.expiresAt > Date.now()
          ? { status: 'ok' } : { status: 'stale' })
      }
      const owner = toRaw(this)
      const pending = renewalTasks.get(owner)
      if (pending?.version === context.operationVersion && pending.revision === context.mainRevision) return pending.promise
      const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
      if (!desktop || context.mainRevision === null) return Promise.resolve({ status: 'failed' })
      const version = context.operationVersion
      const sessionId = this.sessionId
      const current = () => version === this.operationVersion && !this.clearing
      const task = { version, revision: context.mainRevision, promise: Promise.resolve<SessionReadyResult>({ status: 'failed' }) }
      task.promise = (async (): Promise<SessionReadyResult> => {
        let result
        try {
          result = await desktop.ensureAuthSession({ expectedRevision: task.revision, forceRefresh })
        } catch {
          if (!current()) return { status: 'stale' }
          // IPC loss is not permission to retry an old RT. Stop Main's original session instead.
          const cleared = await this.invalidateSession({ reason: 'logout' })
          if (cleared.status !== 'ok') return cleared.status === 'stale' ? cleared : { status: 'failed' }
          this.sessionNotice = '续期结果未能确认，请重新登录。' + (this.sessionNotice ?? '')
          return { status: 'reauthenticate', operationVersion: cleared.operationVersion }
        }
        if (!current()) return { status: 'stale' }
        if (result.status === 'ok') {
          if (!isAuthState(result.authState) || !result.authState.session ||
              result.authState.session.sessionId !== sessionId || result.authState.session.expiresAt <= Date.now()) {
            this.clearError = '无法确认桌面会话，请刷新核对后重试。'
            return { status: 'failed' }
          }
          return this.applyMainState(result.authState) ? { status: 'ok' } : { status: 'stale' }
        }
        if (result.status === 'reauthenticate' && isAuthState(result.authState) && result.authState.session === null &&
            result.authState.revision >= task.revision) {
          this.clearSession()
          this.applyMainState(result.authState)
          this.sessionNotice = '登录会话已失效或续期结果无法确认，请重新登录。'
          return { status: 'reauthenticate', operationVersion: this.operationVersion }
        }
        if (result.status === 'failed' && result.code === 'SESSION_CHANGED') return { status: 'stale' }
        this.clearError = '无法确认桌面会话，请刷新核对后重试。'
        return { status: 'failed' }
      })().finally(() => { if (renewalTasks.get(owner) === task) renewalTasks.delete(owner) })
      renewalTasks.set(owner, task)
      return task.promise
    },

    /** Conditional client-session invalidation; navigation belongs to the caller. */
    invalidateSession(input: InvalidateSessionInput): Promise<InvalidateSessionResult> {
      // Pinia devtools can pass a fresh Proxy as `this` for every action call.
      const taskOwner = toRaw(this)
      const pending = clearingTasks.get(taskOwner)
      if (pending) {
        const sameTarget = input.reason === 'logout' || (
          input.context.operationVersion === pending.targetVersion &&
          input.context.mainRevision === pending.targetRevision
        )
        return sameTarget && this.operationVersion === pending.operationVersion
          ? pending.promise : Promise.resolve({ status: 'stale' })
      }

      if (input.reason !== 'logout' && (
        input.context.operationVersion !== this.operationVersion ||
        input.context.mainRevision !== this.mainRevision ||
        (input.reason === 'expired' && (!this.accessToken || this.expiresAt > Date.now()))
      )) return Promise.resolve({ status: 'stale' })

      const targetVersion = this.operationVersion
      const targetRevision = this.mainRevision
      const targetWebGeneration = this.webGeneration
      const targetSessionId = this.sessionId
      this.clearSession() // Invalidates pending local work once, even if already empty.
      const operationVersion = this.operationVersion
      this.clearing = true
      this.clearError = null
      const current = () => this.operationVersion === operationVersion
      const failed = (code: 'NOT_READY' | 'SESSION_CHANGED' | 'CLEAR_FAILED'): InvalidateSessionResult => {
        if (!current()) return { status: 'stale' }
        this.clearError = code === 'SESSION_CHANGED'
          ? '会话已变化，尚未确认退出，请重新核对会话。'
          : '尚未确认退出，请重试清理会话。'
        return { status: 'failed', code }
      }
      const acceptEmpty = (state: AuthState, minimumRevision: number): InvalidateSessionResult => {
        if (!isAuthState(state) || state.revision < minimumRevision || state.session !== null) {
          return failed('CLEAR_FAILED')
        }
        if (this.mainRevision !== null && state.revision < this.mainRevision) return { status: 'stale' }
        // A late acknowledgement still tells us a confirmed Main revision.
        this.mainRevision = Math.max(this.mainRevision ?? 0, state.revision)
        if (!current()) return { status: 'stale' }
        this.applyMainState(state)
        return { status: 'ok', operationVersion }
      }
      const promise = Promise.resolve().then(async (): Promise<InvalidateSessionResult> => {
        try {
          if (!current()) return { status: 'stale' }
          const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
          if (isWebBrowser()) {
            const result = await webSession().logout(targetWebGeneration, targetSessionId)
            if (!current() || result.status === 'stale') return { status: 'stale' }
            this.webGeneration = webSession().read()?.generation ?? ''
            this.sessionNotice = result.unconfirmed ? '已退出本地登录，但服务端撤销未确认。' : null
            return { status: 'ok', operationVersion }
          }
          if (!desktop) return { status: 'ok', operationVersion }
          if (targetRevision === null || this.initializationStatus !== 'ready') return failed('NOT_READY')
          const reconcile = async (): Promise<InvalidateSessionResult> => {
            if (!current()) return { status: 'stale' }
            // A lost IPC acknowledgement does not prove that Main did not clear.
            // Read once; never adopt a different revision as a new clear target.
            const read = await desktop.readAuthSession()
            if (!current()) return { status: 'stale' }
            if (read.status !== 'ok' || !isAuthState(read.authState)) return failed('CLEAR_FAILED')
            if (read.authState.session !== null) {
              return failed(read.authState.revision > targetRevision ? 'SESSION_CHANGED' : 'CLEAR_FAILED')
            }
            if (input.reason === 'logout') this.sessionNotice = '已退出本地登录，但服务端撤销未确认。'
            return acceptEmpty(read.authState, targetRevision + 1)
          }
          let result
          try {
            result = input.reason === 'logout'
              ? await desktop.logoutAuthSession({ expectedRevision: targetRevision })
              : await desktop.clearAuthSession({ expectedRevision: targetRevision })
          } catch {
            return await reconcile()
          }
          if (result.status === 'ok') {
            if (input.reason !== 'logout' && result.authState?.revision !== targetRevision + 1) return failed('CLEAR_FAILED')
            if (current() && 'serverRevocation' in result) {
              if (result.serverRevocation === 'unconfirmed') this.sessionNotice = '已退出本地登录，但服务端撤销未确认。'
              else if (result.serverRevocation === 'confirmed') this.sessionNotice = null
              // A subsequent local-only clear cannot confirm an earlier failed revocation.
            }
            return acceptEmpty(result.authState, targetRevision + 1)
          }
          if (!current()) return { status: 'stale' }
          if (result.code !== 'SESSION_CHANGED') return failed('CLEAR_FAILED')

          return await reconcile()
        } catch {
          return failed('CLEAR_FAILED')
        }
      }).finally(() => {
        clearingTasks.delete(taskOwner)
        this.clearing = false
      })
      clearingTasks.set(taskOwner, { targetVersion, targetRevision, operationVersion, promise })
      return promise
    },

    /** Capture the login intent before its HTTP request starts. */
    beginLogin(): LoginAttempt {
      if (this.clearing || this.clearError) {
        return { operationVersion: this.operationVersion, mainRevision: this.mainRevision, blocked: true }
      }
      this.operationVersion++
      return { operationVersion: this.operationVersion, mainRevision: this.mainRevision }
    },

    async completeLogin(attempt: LoginAttempt, response: LoginResponse): Promise<LoginCompletionResult> {
      if (attempt.blocked) return { status: 'failed', code: 'CLEAR_PENDING' }
      const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
      if (!desktop) {
        if (attempt.operationVersion !== this.operationVersion) return { status: 'stale' }
        if (isWebBrowser()) {
          if (!response.webSession || !this.applyWebTokens(response.webSession)) return { status: 'failed', code: 'SESSION_CHANGED' }
          return { status: 'ok', operationVersion: this.operationVersion }
        }
        if (!response || typeof response.accessToken !== 'string' || !response.accessToken ||
            response.accessToken.length > 4096 || response.tokenType !== 'Bearer' ||
            !Number.isFinite(response.expiresInSeconds) || response.expiresInSeconds <= 0) {
          return { status: 'failed', code: 'INVALID_RESPONSE' }
        }
        this.accessToken = response.accessToken
        this.tokenType = response.tokenType
        this.expiresInSeconds = response.expiresInSeconds
        this.expiresAt = Date.now() + response.expiresInSeconds * 1000
        return { status: 'ok', operationVersion: this.operationVersion }
      }
      if (this.initializationStatus !== 'ready' || attempt.mainRevision === null) {
        return attempt.operationVersion !== this.operationVersion
          ? { status: 'stale' } : { status: 'failed', code: 'NOT_READY' }
      }
      const saved = response?.authState
      if (!saved || !isAuthState(saved) || !saved.session || saved.revision !== attempt.mainRevision + 1) {
        return attempt.operationVersion !== this.operationVersion
          ? { status: 'stale' } : { status: 'failed', code: 'INVALID_RESPONSE' }
      }
      if (attempt.operationVersion !== this.operationVersion || saved.session.expiresAt <= Date.now()) {
        // Main may already have committed before this local intent became stale.
        // Compensate only for that precise revision; never chase a newer session.
        try {
          const compensationVersion = this.operationVersion
          const cleanup = await desktop.logoutAuthSession({ expectedRevision: saved.revision })
          if (cleanup.status === 'ok' && isAuthState(cleanup.authState) &&
              cleanup.authState.session === null && cleanup.authState.revision >= saved.revision + 1) {
            this.mainRevision = Math.max(this.mainRevision ?? 0, cleanup.authState.revision)
            if (compensationVersion === this.operationVersion && !this.accessToken && cleanup.serverRevocation === 'unconfirmed') {
              this.sessionNotice = '已退出本地登录，但服务端撤销未确认。'
            }
          }
        } catch { /* Remain locally signed out; no retry against a different revision. */ }
        return attempt.operationVersion !== this.operationVersion
          ? { status: 'stale' } : { status: 'failed', code: 'SAVE_FAILED' }
      }
      if (!this.applyMainState(saved)) return { status: 'stale' }
      this.sessionNotice = null
      return { status: 'ok', operationVersion: this.operationVersion }
    },

    /** Apply a Main snapshot locally. This must not write back through IPC. */
    applyMainState(authState: AuthState): boolean {
      const session = authState?.session
      if (!isAuthState(authState)) {
        throw new Error('INVALID_AUTH_STATE')
      }

      if (this.mainRevision !== null && authState.revision < this.mainRevision) return false

      const now = Date.now()
      this.mainRevision = authState.revision
      if (session === null) {
        this.accessToken = ''
        this.tokenType = ''
        this.expiresInSeconds = 0
        this.expiresAt = 0
        this.sessionId = ''
        this.sessionExpiresAt = 0
        this.profile = null
        this.profileSessionId = ''
      } else {
        this.accessToken = session.accessToken
        this.tokenType = session.tokenType
        this.expiresAt = session.expiresAt
        this.sessionId = session.sessionId
        this.sessionExpiresAt = session.sessionExpiresAt
        // Compatibility field: remaining seconds at restoration, never a new TTL.
        this.expiresInSeconds = Math.ceil((session.expiresAt - now) / 1000)
      }
      return true
    },

    /** The caller must check initializationStatus before allowing initial navigation. */
    initializeSession(): Promise<void> {
      if (this.initializationStatus === 'ready') return Promise.resolve()
      const taskOwner = toRaw(this)
      const pending = initializationTasks.get(taskOwner)
      if (pending) return pending

      const versionAtStart = this.operationVersion
      this.initializationStatus = 'loading'
      this.initializationError = null

      const task = Promise.resolve().then(async () => {
        try {
          const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
          if (!desktop) {
            if (isWebBrowser()) {
              const coordinator = webSession()
              if (!webSubscriptions.has(taskOwner)) {
                coordinator.subscribe(state => this.observeWebState(state))
                webSubscriptions.add(taskOwner)
              }
              await this.restoreWebSession()
            }
            this.initializationStatus = 'ready'
            return
          }
          if (typeof desktop.readAuthSession !== 'function') throw new Error('UNSUPPORTED_BRIDGE')

          const result = await desktop.readAuthSession()
          if (this.operationVersion !== versionAtStart) {
            this.initializationStatus = 'idle'
            return
          }
          if (result.status !== 'ok') throw new Error('AUTH_SESSION_READ_FAILED')

          this.applyMainState(result.authState)
          if (result.serverRevocation) this.sessionNotice = '已退出本地登录，但服务端撤销未确认。'
          this.initializationStatus = 'ready'
        } catch {
          if (this.operationVersion !== versionAtStart) {
            this.initializationStatus = 'idle'
            return
          }
          this.initializationStatus = 'failed'
          this.initializationError = isWebBrowser() ? '此浏览器无法安全协调登录状态，请使用支持 Web Locks、广播和本地存储的浏览器。' : '无法恢复桌面登录状态，请重试。'
        }
      }).finally(() => {
        initializationTasks.delete(taskOwner)
      })

      initializationTasks.set(taskOwner, task)
      return task
    },

    /**
     * 一次性设置会话，传入后端登录返回的 LoginResponse
     * 自动换算 expiresAt：当前时间 + 有效时长
     */
    setSession(loginResp: LoginResponse) {
      this.operationVersion++
      this.accessToken = loginResp.accessToken
      this.tokenType = loginResp.tokenType
      this.expiresInSeconds = loginResp.expiresInSeconds
      // Date.now() 是毫秒；expiresInSeconds是秒，*1000
      this.expiresAt = Date.now() + loginResp.expiresInSeconds * 1000
    },

    /** 清空会话，退出登录 */
    clearSession() {
      this.operationVersion++
      this.accessToken = ""
      this.tokenType = ""
      this.expiresInSeconds = 0
      this.expiresAt = 0
      this.sessionId = ''
      this.sessionExpiresAt = 0
      this.profile = null
      this.profileSessionId = ''
    },

    async ensureProfile(): Promise<AuthMeResponse | null> {
      if (!this.accessToken || this.expiresAt <= Date.now()) {
        this.profile = null
        this.profileSessionId = ''
        return null
      }
      if (this.profile && this.profileSessionId === this.sessionId) {
        return this.profile
      }
      const versionAtStart = this.operationVersion
      const sessionAtStart = this.sessionId
      try {
        const { getMe } = await import('@/api/auth')
        const res = await getMe()
        if (this.operationVersion !== versionAtStart || this.sessionId !== sessionAtStart) {
          return null
        }
        this.profile = res.data
        this.profileSessionId = sessionAtStart
        return res.data
      } catch {
        return null
      }
    },
  },
})
