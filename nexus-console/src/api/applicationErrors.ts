import { isAxiosError } from 'axios'
import type { ApiResponse } from './http'

export function applicationErrorMessage(error: unknown, fallback: string): string {
  if (!isAxiosError<ApiResponse<unknown>>(error)) return fallback
  if (!error.response) return '无法连接服务器，请确认后端已启动'

  switch (error.response.data?.code) {
    case 'INVALID_ACCESS_TOKEN':
      return '登录已过期，请重新登录'
    case 'AUTH_ACCOUNT_FORBIDDEN':
      return '账号已被禁用，无法管理应用'
    case 'APPLICATION_NAME_ALREADY_EXISTS':
      return '这个应用名称已被使用，请换一个名称'
    case 'APPLICATION_NOT_FOUND':
      return '应用不存在或已被删除，请刷新列表'
    case 'INVALID_LIST_PAGE':
      return error.response.data.message || '页码或每页条数无效'
    case 'VALIDATION_ERROR':
    case 'INVALID_APPLICATION_UPDATE':
      return '请检查应用名称和状态后重试'
    default:
      return fallback
  }
}
