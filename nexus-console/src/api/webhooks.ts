import { http, type ApiResponse } from './http'

export interface ApplicationWebhook {
  id: number
  applicationId: number
  url: string
  status: 0 | 1
  currentSecretVersion: number
  createdAt: string
  updatedAt: string
}

export interface CreatedApplicationWebhook extends ApplicationWebhook {
  secret: string
}

export interface RotatedWebhookSecret {
  webhook: ApplicationWebhook
  secret: string
}

export interface UpdateWebhookRequest {
  url?: string
  status?: 0 | 1
}

export async function queryApplicationWebhook(applicationId: number): Promise<ApplicationWebhook | null> {
  const response = await http.get<ApiResponse<ApplicationWebhook | null>>(`/application/${applicationId}/webhook`)
  return response.data.data
}

export async function createApplicationWebhook(applicationId: number, url: string): Promise<CreatedApplicationWebhook> {
  const response = await http.post<ApiResponse<CreatedApplicationWebhook>>(`/application/${applicationId}/webhook`, { url })
  return response.data.data
}

export async function updateApplicationWebhook(applicationId: number, request: UpdateWebhookRequest): Promise<ApplicationWebhook> {
  const response = await http.put<ApiResponse<ApplicationWebhook>>(`/application/${applicationId}/webhook`, request)
  return response.data.data
}

export async function rotateApplicationWebhookSecret(applicationId: number, currentSecretVersion: number): Promise<RotatedWebhookSecret> {
  const response = await http.post<ApiResponse<RotatedWebhookSecret>>(
    `/application/${applicationId}/webhook/rotate-secret`,
    { currentSecretVersion },
  )
  return response.data.data
}
