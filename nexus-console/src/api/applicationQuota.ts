import { http, type ApiResponse } from './http'

export interface ApplicationQuota {
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
