import { http, type ApiResponse } from './http'

export interface ApplicationQuota {
  // 带 Z 的 UTC 时刻；结束时刻属于下一周期。
  periodStart: string
  periodEnd: string
  quotaLimit: number
  usedCount: number
  restCount: number
}

export async function queryApplicationQuota(applicationId: number): Promise<ApplicationQuota> {
  const response = await http.get<ApiResponse<ApplicationQuota>>(`/application/${applicationId}/quota`)
  return response.data.data
}
