import type { Trace } from '../api/Trace'

export type SaveScreenshotResult =
  | { status: 'saved' }
  | { status: 'cancelled' }
  | {
  status: 'failed'
  code: 'INVALID_IMAGE' | 'FORBIDDEN' | 'BUSY' | 'SAVE_FAILED'
}

export type OpenShortLinkResult =
  | { status: 'opened' }
  | { status: 'failed'; code: 'FORBIDDEN' | 'INVALID_REQUEST' | 'OPEN_FAILED' }

export interface AuthSession {
  accessToken: string
  tokenType: 'Bearer'
  /** Absolute expiry time in milliseconds; restoring must preserve it. */
  expiresAt: number
  sessionId: string
  /** Absolute session deadline in milliseconds; unchanged by rotation. */
  sessionExpiresAt: number
}

export interface AuthState {
  revision: number
  session: AuthSession | null
}

export interface LoginAuthSessionInput {
  expectedRevision: number
  email: string
  password: string
}

export interface ClearAuthSessionInput {
  expectedRevision: number
}

export type ReadAuthSessionResult =
  | { status: 'ok'; authState: AuthState; code?: 'SESSION_IS_NULL'; serverRevocation?: 'pending' | 'unconfirmed' }
  | { status: 'failed'; code: 'FORBIDDEN' }

export type LoginAuthSessionResult =
  | { status: 'ok'; authState: AuthState; code: 'SUCCESS' }
  | { status: 'failed'; httpStatus: number; code:
      'FORBIDDEN' | 'INVALID_REQUEST' | 'SESSION_CHANGED' | 'INVALID_RESPONSE' |
      'UPSTREAM_UNAVAILABLE' | 'TIMEOUT' | 'AUTH_INVALID_CREDENTIALS' |
      'AUTH_ACCOUNT_FORBIDDEN' | 'AUTH_SESSION_UNAVAILABLE' }

export interface EnsureAuthSessionInput {
  expectedRevision: number
  forceRefresh?: boolean
}

export type EnsureAuthSessionResult =
  | { status: 'ok'; authState: AuthState }
  | { status: 'reauthenticate'; code: string; authState: AuthState }
  | { status: 'failed'; code: string }

export type LogoutAuthSessionResult =
  | { status: 'ok'; authState: AuthState; serverRevocation: 'confirmed' | 'unconfirmed' | 'not-needed' }
  | { status: 'failed'; code: string }

export type ClearAuthSessionResult =
  | { status: 'ok'; authState: AuthState; code: 'SUCCESS' }
  | { status: 'failed'; code: 'FORBIDDEN' | 'INVALID_REQUEST' | 'SESSION_CHANGED' }

export interface NexusDesktop {
  openShortLink?(shortCode: string): Promise<OpenShortLinkResult>

  saveScreenshot(bytes: ArrayBuffer): Promise<SaveScreenshotResult>

  startScreenshotTracking(
    input: StartScreenshotTrackingInput,
  ): Promise<StartScreenshotTrackingResult>

  readScreenshotTracking(): Promise<ReadScreenshotTrackingResult>

  stopScreenshotTracking(): Promise<StopScreenshotTrackingResult>

  onScreenshotTrackingUpdated(
    listener: ScreenshotTrackingListener,
  ): () => void

  getScreenshotResult(jobId: string): Promise<GetScreenshotResult>

  readAuthSession(): Promise<ReadAuthSessionResult>

  loginAuthSession(input: LoginAuthSessionInput): Promise<LoginAuthSessionResult>

  ensureAuthSession(input: EnsureAuthSessionInput): Promise<EnsureAuthSessionResult>

  logoutAuthSession(input: ClearAuthSessionInput): Promise<LogoutAuthSessionResult>

  clearAuthSession(input: ClearAuthSessionInput): Promise<ClearAuthSessionResult>
}

declare global {
  interface Window {
    nexusDesktop?: NexusDesktop
  }
}

export interface StartScreenshotTrackingInput {
  jobId: string
  apiKey: string
}

export type StartScreenshotTrackingResult =
  | { status: 'ok'; snapshot: Trace }
  | {
  status: 'failed'
  code: 'INVALID_REQUEST' | 'FORBIDDEN' | 'BUSY'
}

export type ReadScreenshotTrackingResult =
  | { status: 'ok'; snapshot: Trace }
  | { status: 'failed'; code: 'FORBIDDEN' }

export type StopScreenshotTrackingResult =
  | { status: 'ok'; snapshot: Trace }
  | { status: 'failed'; code: 'FORBIDDEN' }

export type ScreenshotTrackingListener = (snapshot: Trace) => void

export type GetScreenshotResult =
  | {
  status: 'ok'
  jobId: string
  bytes: ArrayBuffer
  cacheControl: string | null
}
  | {
  status: 'failed'
  code:
    | 'FORBIDDEN'
    | 'INVALID_REQUEST'
    | 'NOT_READY'
    | 'STALE_TASK'
    | 'BUSY'
    | 'FETCH_FAILED'
    | 'INVALID_IMAGE'
}
