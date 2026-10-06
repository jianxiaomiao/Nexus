import axios, { isAxiosError, type AxiosRequestConfig } from 'axios'
import { useUserStore } from '@/stores/userStore'

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

// 人类用户的 Bearer token 只用于管理接口，不发送给登录/注册或机器 API。
const managementPaths = ['/application', '/apiKey', '/short-links']

function isManagementRequest(config: AxiosRequestConfig): boolean {
  if (config.baseURL !== '/api' || !config.url) return false
  const url = new URL(config.url, 'http://nexus.invalid')
  if (url.origin !== 'http://nexus.invalid') return false
  return managementPaths.some((path) => url.pathname === path || url.pathname.startsWith(`${path}/`))
}

http.interceptors.request.use((config) => {
  if (!isManagementRequest(config)) return config

  const userStore = useUserStore()
  if (!userStore.accessToken) return config
  if (userStore.expiresAt <= Date.now()) {
    userStore.clearSession()
    return config
  }

  config.headers.set('Authorization', `Bearer ${userStore.accessToken}`)
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.config &&
      isManagementRequest(error.config) &&
      error.response?.status === 401 &&
      error.response.data?.code === 'INVALID_ACCESS_TOKEN'
    ) {
      const userStore = useUserStore()
      const sentAuthorization = error.config.headers.get('Authorization')
      // 旧请求迟到的 401 不能清掉用户刚刚重新登录取得的新会话。
      if (userStore.accessToken && sentAuthorization === `Bearer ${userStore.accessToken}`) {
        userStore.clearSession()
      }
    }
    return Promise.reject(error)
  },
)
