import { isAxiosError } from 'axios'
import { http, type ApiResponse } from './http'

export type UsageTimeRange =
  | 'TODAY'
  | 'YESTERDAY'
  | 'LAST_7_DAYS'
  | 'LAST_30_DAYS'
  | 'THIS_MONTH'
  | 'LAST_MONTH'
  | 'CUSTOM'

export interface UsageQuery {
  applicationId: number
  apiKeyId: number
  timeRange: UsageTimeRange
  customStartTime?: string
  customEndTime?: string
}

export interface UsageSummary {
  allTimeCount: number
  periodCount: number
  timeZone: string
  periodStart: string
  periodEndExclusive: string
  dailyCounts: { date: string; count: number }[]
  apiCounts: { apiCode: string; apiName: string; count: number }[]
}

export async function queryUsage(query: UsageQuery): Promise<UsageSummary> {
  const response = await http.get<ApiResponse<UsageSummary>>('/usage', { params: query })
  return response.data.data
}

export function usageErrorMessage(error: unknown): string {
  if (!isAxiosError<ApiResponse<unknown>>(error)) return '加载调用统计失败，请稍后重试'
  if (!error.response) return '无法连接服务器，请确认后端已启动'
  switch (error.response.data?.code) {
    case 'INVALID_ACCESS_TOKEN':
      return '登录已过期，请重新登录'
    case 'AUTH_ACCOUNT_FORBIDDEN':
      return '账号已被禁用，无法查看调用统计'
    case 'APPLICATION_NOT_FOUND':
    case 'API_KEY_NOT_FOUND':
      return '这枚 API Key 不存在，或你没有访问权限'
    case 'INVALID_USAGE_TIME_RANGE':
      return error.response.data.message || '时间范围无效，请重新选择'
    default:
      return '加载调用统计失败，请稍后重试'
  }
}
