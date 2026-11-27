import { AxiosError, AxiosHeaders } from 'axios'
import type { AuthState } from '@/types/desktop'
import { http, type ApiResponse } from './http'
import { isWebBrowser, webSession, WebSessionError, type WebTokens } from '@/auth/webSession'

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  /** Desktop only: Main already saved the private session before returning. */
  authState?: AuthState
  webSession?: WebTokens
}

export async function login(request: LoginRequest, expectedRevision?: number | null, isCurrent?: () => boolean): Promise<ApiResponse<LoginResponse>> {
  const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
  if (desktop) {
    if (expectedRevision === null || expectedRevision === undefined || typeof desktop.loginAuthSession !== 'function') {
      throw new Error('DESKTOP_LOGIN_NOT_READY')
    }
    const result = await desktop.loginAuthSession({ ...request, expectedRevision })
    if (result.status !== 'ok') {
      // Preserve the existing login UI's HTTP error handling without reflecting upstream bodies.
      const data = { code: result.code, message: '桌面登录未完成', data: null }
      throw new AxiosError('Desktop login failed', result.code, undefined, undefined, {
        status: result.httpStatus, statusText: '', headers: {}, config: { headers: new AxiosHeaders() }, data,
      })
    }
    const session = result.authState.session
    if (!session) throw new Error('DESKTOP_LOGIN_EMPTY')
    return { code: 'SUCCESS', message: '登录成功', data: {
      accessToken: session.accessToken, tokenType: session.tokenType,
      expiresInSeconds: Math.max(0, Math.floor((session.expiresAt - Date.now()) / 1000)),
      authState: result.authState,
    } }
  }
  if (isWebBrowser()) {
    try {
      const result = await webSession().login(request, isCurrent)
      if (result.status !== 'ok') throw new WebSessionError(409, 'SESSION_CHANGED')
      return { code: 'SUCCESS', message: '登录成功', data: { ...result.tokens, webSession: result.tokens } }
    } catch (error) {
      if (!(error instanceof WebSessionError)) throw error
      throw new AxiosError('Web login failed', error.code, undefined, undefined, {
        status: error.status, statusText: '', headers: {}, config: { headers: new AxiosHeaders() },
        data: { code: error.code, message: '网页登录未完成', data: null },
      })
    }
  }
  const response = await http.post<ApiResponse<LoginResponse>>('/auth/login', request)
  return response.data
}

export interface AuthMeResponse {
  id: number
  email: string
  displayName: string
  role: 'USER' | 'ADMIN' | string
  status: number
}

export async function getMe(): Promise<ApiResponse<AuthMeResponse>> {
  const response = await http.get<ApiResponse<AuthMeResponse>>('/auth/me')
  return response.data
}

export interface RegisterRequest {
  email: string
  displayName: string
  password: string
  verificationCode: string
}

export interface RegisterResponse {
  email: string
  displayName: string
}

export async function sendRegisterEmailCode(email: string): Promise<ApiResponse<void>> {
  const response = await http.post<ApiResponse<void>>('/auth/register/email-code', { email })
  return response.data
}

export async function register(request: RegisterRequest): Promise<ApiResponse<RegisterResponse>> {
  const response = await http.post<ApiResponse<RegisterResponse>>('/auth/register', request)
  return response.data
}
