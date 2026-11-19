import { MutationResultUnknown } from './mutation'
import { checkedListPage } from './listPage'
import { http, type ApiResponse } from './http'
import type { ListPage, ListPageQuery } from './listPage'

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

export interface RotatedApiKey extends Omit<ApiKey, 'createdAt'> {
  apiKey: string
}

export interface UpdateApiKeyRequest {
  expectedUpdatedAt?: string
  applicationId: number
  apiKeyId: number
  name?: string
  status?: 0 | 1
}

export async function listApiKeys(applicationId: number, query: ListPageQuery & { apiKeyId?: number } = {}): Promise<ListPage<ApiKey>> {
  const response = await http.get<ApiResponse<ListPage<ApiKey>>>(`/apiKey/${applicationId}`, { params: query })
  return checkedListPage(response.data.data)
}

export async function createApiKey(applicationId: number, name: string): Promise<CreatedApiKey> {
  const response = await http.post<ApiResponse<CreatedApiKey>>('/apiKey/create', { applicationId, name })
  if (response.data?.code !== 'SUCCESS' || typeof response.data.data?.apiKey !== 'string' || !response.data.data.apiKey) throw new MutationResultUnknown()
  return response.data.data
}

export async function updateApiKey(request: UpdateApiKeyRequest): Promise<ApiKey> {
  const response = await http.put<ApiResponse<ApiKey>>('/apiKey', request)
  const data = response.data.data
  if (!data || data.id !== request.apiKeyId || data.applicationId !== request.applicationId || typeof data.updatedAt !== 'string') throw new MutationResultUnknown()
  return data
}

export async function deleteApiKey(applicationId: number, apiKeyId: number): Promise<void> {
  await http.delete('/apiKey', { data: { applicationId, apiKeyId } })
}

export async function rotateApiKey(apiKeyId: number, expectedPublicId: string): Promise<RotatedApiKey> {
  const response = await http.post<ApiResponse<RotatedApiKey>>(`/apiKey/${apiKeyId}/rotate`, { expectedPublicId })
  if (response.data?.code !== 'SUCCESS' || typeof response.data.data?.apiKey !== 'string' || !response.data.data.apiKey) throw new MutationResultUnknown()
  return response.data.data
}
