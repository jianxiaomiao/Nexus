import { defineStore } from 'pinia'
import { toRaw } from 'vue'
import type { LoginResponse } from "@/api/auth"
import type { AuthState } from '@/types/desktop'

type InitializationStatus = 'idle' | 'loading' | 'ready' | 'failed'

export interface LoginAttempt {
  operationVersion: number
  mainRevision: number | null
  blocked?: true
}

export type SessionContext = Pick<LoginAttempt, 'operationVersion' | 'mainRevision'>

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

function isAuthState(state: AuthState): boolean {
  const session = state?.session
  return Number.isSafeInteger(state?.revision) && state.revision >= 0 && (
    session === null || (
      !!session && typeof session === 'object' && !Array.isArray(session) &&
      typeof session.accessToken === 'string' && session.accessToken.length > 0 &&
      session.accessToken.length <= 4096 && session.tokenType === 'Bearer' &&
      Number.isFinite(session.expiresAt)
    )
  )
}

type UserSession = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  expiresAt: number // 绝对过期时间戳（毫秒）
  mainRevision: number | null
  operationVersion: number
  initializationStatus: InitializationStatus
  initializationError: string | null
  clearing: boolean
  clearError: string | null
}

export const useUserStore = defineStore('userStore', {
  state: (): UserSession => ({
    accessToken: "",
    tokenType: "",
    expiresInSeconds: 0,
    expiresAt: 0,
    mainRevision: null,
    operationVersion: 0,
    initializationStatus: 'idle',
    initializationError: null,
    clearing: false,
    clearError: null,
  }),

  // 删掉那些单纯返回state的getter，直接 store.xxx 访问即可

  actions: {
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
            return acceptEmpty(read.authState, targetRevision + 1)
          }
          let result
          try {
            result = await desktop.clearAuthSession({ expectedRevision: targetRevision })
          } catch {
            return await reconcile()
          }
          if (result.status === 'ok') {
            if (result.authState?.revision !== targetRevision + 1) return failed('CLEAR_FAILED')
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
      if (attempt.operationVersion !== this.operationVersion) return { status: 'stale' }
      if (attempt.blocked) return { status: 'failed', code: 'CLEAR_PENDING' }
      if (!response || typeof response !== 'object') return { status: 'failed', code: 'INVALID_RESPONSE' }
      const expiresAt = Date.now() + response.expiresInSeconds * 1000
      if (
        typeof response.accessToken !== 'string' || !response.accessToken || response.accessToken.length > 4096 ||
        response.tokenType !== 'Bearer' || !Number.isFinite(response.expiresInSeconds) ||
        response.expiresInSeconds <= 0 || !Number.isFinite(expiresAt)
      ) return { status: 'failed', code: 'INVALID_RESPONSE' }

      const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
      if (!desktop) {
        this.accessToken = response.accessToken
        this.tokenType = response.tokenType
        this.expiresInSeconds = response.expiresInSeconds
        this.expiresAt = expiresAt
        return { status: 'ok', operationVersion: this.operationVersion }
      }
      if (this.initializationStatus !== 'ready' || attempt.mainRevision === null) {
        return { status: 'failed', code: 'NOT_READY' }
      }

      try {
        const result = await desktop.writeAuthSession({
          expectedRevision: attempt.mainRevision,
          session: { accessToken: response.accessToken, tokenType: 'Bearer', expiresAt },
        })
        if (result.status !== 'ok') {
          if (attempt.operationVersion !== this.operationVersion) return { status: 'stale' }
          return { status: 'failed', code: result.code === 'SESSION_CHANGED' ? 'SESSION_CHANGED' : 'SAVE_FAILED' }
        }
        const saved = result.authState
        if (
          saved.revision !== attempt.mainRevision + 1 ||
          saved.session?.accessToken !== response.accessToken ||
          saved.session.tokenType !== 'Bearer' || saved.session.expiresAt !== expiresAt
        ) return { status: 'failed', code: 'SAVE_FAILED' }

        if (attempt.operationVersion !== this.operationVersion || expiresAt <= Date.now()) {
          // A stale acknowledgement may still represent a completed Main write.
          // Clear only that exact revision; never retry against a newer session.
          const cleanup = await desktop.clearAuthSession({ expectedRevision: saved.revision })
          if (cleanup.status === 'ok') {
            this.mainRevision = Math.max(this.mainRevision ?? 0, cleanup.authState.revision)
          }
          return attempt.operationVersion !== this.operationVersion
            ? { status: 'stale' } : { status: 'failed', code: 'SAVE_FAILED' }
        }
        if (!this.applyMainState(saved)) return { status: 'stale' }
        return { status: 'ok', operationVersion: this.operationVersion }
      } catch {
        return attempt.operationVersion !== this.operationVersion
          ? { status: 'stale' } : { status: 'failed', code: 'SAVE_FAILED' }
      }
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
      if (session === null || session.expiresAt <= now) {
        this.accessToken = ''
        this.tokenType = ''
        this.expiresInSeconds = 0
        this.expiresAt = 0
      } else {
        this.accessToken = session.accessToken
        this.tokenType = session.tokenType
        this.expiresAt = session.expiresAt
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
            // Web has no Main process: preserve its existing in-memory session.
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
          this.initializationStatus = 'ready'
        } catch {
          if (this.operationVersion !== versionAtStart) {
            this.initializationStatus = 'idle'
            return
          }
          this.initializationStatus = 'failed'
          this.initializationError = '无法恢复桌面登录状态，请重试。'
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
    },
  },
})
