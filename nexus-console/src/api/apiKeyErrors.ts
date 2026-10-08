import { isAxiosError } from 'axios'
import type { ApiResponse } from './http'

export function apiKeyErrorMessage(error: unknown, fallback: string): string {
  if (!isAxiosError<ApiResponse<unknown>>(error)) return fallback
  if (!error.response) return '无法连接服务器，请确认后端已启动'

  switch (error.response.data?.code) {
    case 'INVALID_ACCESS_TOKEN':
      return '登录已过期，请重新登录'
    case 'AUTH_ACCOUNT_FORBIDDEN':
      return '账号已被禁用，无法管理 API Key'
    case 'APPLICATION_NOT_FOUND':
      return '所属应用不存在，或你没有访问权限'
    case 'APPLICATION_DISABLED':
      return '所属应用已禁用，暂时不能创建 API Key'
    case 'API_KEY_NAME_ALREADY_EXISTS':
      return '这个 Key 名称已被使用，请换一个名称'
    case 'API_KEY_NOT_FOUND':
      return '这个 API Key 不存在，或已被删除'
    case 'INVALID_LIST_PAGE':
      return error.response.data.message || '页码或每页条数无效'
    case 'VALIDATION_ERROR':
    case 'INVALID_API_KEY_UPDATE':
    case 'INVALID_API_KEY_DELETE':
      return '请检查提交的信息后重试'
    default:
      return fallback
  }
}
