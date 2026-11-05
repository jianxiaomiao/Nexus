import { isAxiosError } from 'axios'
import type { ApiResponse } from './http'

export function webhookErrorMessage(error: unknown, fallback: string): string {
  if (!isAxiosError<ApiResponse<unknown>>(error)) return fallback
  if (!error.response) return '无法连接服务器，请确认后端已启动'

  switch (error.response.data?.code) {
    case 'INVALID_ACCESS_TOKEN':
      return '登录已过期，请重新登录'
    case 'AUTH_ACCOUNT_FORBIDDEN':
      return '账号已被禁用，无法管理 Webhook'
    case 'APPLICATION_NOT_FOUND':
      return '应用不存在，或你没有访问权限'
    case 'WEBHOOK_NOT_FOUND':
      return 'Webhook 配置不存在，请刷新页面'
    case 'WEBHOOK_ALREADY_EXISTS':
      return '这个应用已经配置了 Webhook，请刷新页面'
    case 'WEBHOOK_URL_INVALID':
      return '请输入不含账号、Fragment 的完整 HTTPS 地址'
    case 'WEBHOOK_DNS_RESOLUTION_FAILED':
      return '暂时无法解析回调域名，请检查域名后重试'
    case 'WEBHOOK_ADDRESS_DISALLOWED':
      return '回调地址解析到了私网、回环或其他禁止访问的地址'
    case 'WEBHOOK_SECRET_ROTATION_CONFLICT':
      return 'Secret 已被其他请求轮换，请刷新配置后再操作'
    case 'VALIDATION_ERROR':
    case 'INVALID_WEBHOOK_UPDATE':
      return '请检查回调地址和状态后重试'
    default:
      return fallback
  }
}
