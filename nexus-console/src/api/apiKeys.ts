import { http, type ApiResponse } from './http'

export interface ApiKey {
  id: number
  applicationId: number
  name: string
  publicId: string
  keyPreview: string
  status: number
  createdAt: string
  updatedAt: string
}

export interface CreatedApiKey extends Omit<ApiKey, 'updatedAt'> {
  apiKey: string
}

export interface UpdateApiKeyRequest {
  applicationId: number
  apiKeyId: number
  name?: string
  status?: 0 | 1
}

export async function listApiKeys(applicationId: number): Promise<ApiKey[]> {
  const response = await http.get<ApiResponse<{ apiKeyResponseList: ApiKey[] }>>(`/apiKey/${applicationId}`)
  return response.data.data.apiKeyResponseList
}

export async function createApiKey(applicationId: number, name: string): Promise<CreatedApiKey> {
  const response = await http.post<ApiResponse<CreatedApiKey>>('/apiKey/create', { applicationId, name })
  return response.data.data
}

export async function updateApiKey(request: UpdateApiKeyRequest): Promise<ApiKey> {
  const response = await http.put<ApiResponse<ApiKey>>('/apiKey', request)
  return response.data.data
}

export async function deleteApiKey(applicationId: number, apiKeyId: number): Promise<void> {
  await http.delete('/apiKey', { data: { applicationId, apiKeyId } })
}
