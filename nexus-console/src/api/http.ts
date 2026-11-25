import axios, { CanceledError, isAxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { isNavigationFailure, type Router } from 'vue-router'
import { useUserStore, type SessionContext, type SessionReadyResult } from '@/stores/userStore'
import { isWebBrowser } from '@/auth/webSession'
import { MutationResultUnknown } from './mutation'

declare module 'axios' {
  interface AxiosRequestConfig {
    // Local metadata only: never sent in the body, headers or query string.
    authContext?: SessionContext
  }
}

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

let sessionRouter: Pick<Router, 'replace' | 'currentRoute'> | undefined
let loginNavigation: { operationVersion: number; promise: ReturnType<Router['replace']> } | undefined

// The composition layer supplies navigation; API modules do not import page routes.
export function configureSessionNavigation(router: Pick<Router, 'replace' | 'currentRoute'>) {
  sessionRouter = router
}

// 人类用户的 Bearer token 只用于管理接口，不发送给登录/注册或机器 API。
const managementPaths = ['/application', '/apiKey', '/short-links', '/usage', '/support/tickets', '/support/chat/launch', '/support/chat/unread']

function isManagementRequest(config: AxiosRequestConfig): boolean {
  if (config.baseURL !== '/api' || !config.url) return false
  const url = new URL(config.url, 'http://nexus.invalid')
  if (url.origin !== 'http://nexus.invalid') return false
  return managementPaths.some((path) => url.pathname === path || url.pathname.startsWith(`${path}/`))
}

async function navigateAfterSessionResult(result: SessionReadyResult) {
  const store = useUserStore()
  if (result.status === 'ok' || result.status === 'stale') return
  if (result.status === 'failed') {
    ElMessage.error({ message: store.clearError ?? '无法确认会话状态，请刷新重试。', grouping: true })
    return
  }
  if (result.operationVersion !== store.operationVersion || sessionRouter?.currentRoute.value.name === 'login') return
  let navigation: typeof loginNavigation
  try {
    if (!sessionRouter) throw new Error('SESSION_NAVIGATION_NOT_CONFIGURED')
    if (loginNavigation?.operationVersion !== result.operationVersion) {
      loginNavigation = {
        operationVersion: result.operationVersion,
        promise: sessionRouter.replace({ name: 'login' }),
      }
    }
    navigation = loginNavigation
    const failure = await navigation.promise
    if (isNavigationFailure(failure) && result.operationVersion === store.operationVersion) {
      ElMessage.error({ message: '会话已清理，但页面切换失败，请刷新页面。', grouping: true })
    }
  } catch {
    if (result.operationVersion === store.operationVersion) {
      ElMessage.error({ message: '会话已清理，但页面切换失败，请刷新页面。', grouping: true })
    }
  } finally {
    if (navigation && loginNavigation === navigation) loginNavigation = undefined
  }
}

async function invalidateAndNavigate(reason: 'expired' | 'unauthorized', context: SessionContext) {
  const store = useUserStore()
  const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
  if (desktop || isWebBrowser()) {
    const result = await store.ensureSession(context, reason === 'unauthorized')
    await navigateAfterSessionResult(result)
    return result.status === 'ok'
  }
  const cleared = await store.invalidateSession({ reason, context })
  await navigateAfterSessionResult(cleared.status === 'ok'
    ? { status: 'reauthenticate', operationVersion: cleared.operationVersion } : cleared)
  return false
}

http.interceptors.request.use(async (config) => {
  delete config.authContext
  if (!isManagementRequest(config)) return config

  const userStore = useUserStore()
  if (!userStore.checkWebState()) {
    await navigateAfterSessionResult({ status: 'reauthenticate', operationVersion: userStore.operationVersion })
    throw new CanceledError('SESSION_CHANGED', config)
  }
  if (userStore.clearing || userStore.clearError) {
    throw new CanceledError('SESSION_CLEAR_PENDING', config)
  }
  if (!userStore.accessToken) return config
  const context = userStore.sessionContext()
  if (userStore.expiresAt <= Date.now()) {
    const renewed = await invalidateAndNavigate('expired', context)
    if (!renewed || userStore.clearing || userStore.clearError || userStore.expiresAt <= Date.now()) {
      throw new CanceledError('SESSION_EXPIRED', config)
    }
  }

  if (context.operationVersion !== userStore.operationVersion || userStore.clearing || userStore.clearError) {
    throw new CanceledError('SESSION_CHANGED', config)
  }
  config.headers.set('Authorization', `Bearer ${userStore.accessToken}`)
  config.authContext = userStore.sessionContext()
  return config
})

function staleContext(config: AxiosRequestConfig): boolean {
  if (!config.authContext) return false
  const user = useUserStore()
  return config.authContext.operationVersion !== user.operationVersion || !user.accessToken || user.clearing || !user.checkWebState()
}

http.interceptors.response.use(
  (response) => {
    if (staleContext(response.config)) throw new CanceledError('SESSION_CHANGED', response.config)
    if (isManagementRequest(response.config) && (response.data?.code !== 'SUCCESS' ||
        !Object.prototype.hasOwnProperty.call(response.data, 'data'))) {
      if (response.config.method?.toUpperCase() !== 'GET') throw new MutationResultUnknown()
      throw new Error('INVALID_MANAGEMENT_RESPONSE')
    }
    return response
  },
  async (error) => {
    if (isAxiosError(error) && error.config && staleContext(error.config)) throw new CanceledError('SESSION_CHANGED', error.config)
    if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.config &&
      isManagementRequest(error.config) &&
      error.response?.status === 401 &&
      error.response.data?.code === 'INVALID_ACCESS_TOKEN' &&
      error.config.authContext &&
      typeof error.config.headers.get('Authorization') === 'string' &&
      String(error.config.headers.get('Authorization')).startsWith('Bearer ')
    ) {
      // Compare the original request snapshot, never the state at response time.
      await invalidateAndNavigate('unauthorized', error.config.authContext)
    }
    return Promise.reject(error)
  },
)
