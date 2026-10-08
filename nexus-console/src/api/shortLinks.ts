import { isAxiosError } from 'axios'
import { http, type ApiResponse } from './http'
import type { ListPage, ListPageQuery } from './listPage'

export interface ShortLink {
  id: number
  apiKeyId: number
  name: string
  originalUrl: string
  shortCode: string
  status: 0 | 1
  expiresAt: string
  createdAt: string
  updatedAt: string
}

export interface CreateShortLinkRequest {
  name: string
  originalUrl: string
  expiresAt: string
}

export interface UpdateShortLinkRequest {
  id: number
  name?: string
  status?: 0 | 1
}

export type ShortLinkOperation = 'create' | 'list' | 'update' | 'delete'

export interface ShortLinkOpenApiResult {
  status: number
  body: ApiResponse<ShortLink | ShortLink[] | null>
}

// 公开短地址与前端同源；部署到独立短链域名时可设置 VITE_SHORT_LINK_PUBLIC_BASE_URL。
export function publicShortLinkUrl(shortCode: string): string {
  const base = (import.meta.env.VITE_SHORT_LINK_PUBLIC_BASE_URL || window.location.origin).replace(/\/$/, '')
  return `${base}/s/${encodeURIComponent(shortCode)}`
}

// 机器接口只使用调用者输入的完整 Key，不走携带 Bearer JWT 的管理客户端。
export async function callShortLinkOpenApi(
  operation: ShortLinkOperation,
  apiKey: string,
  payload?: CreateShortLinkRequest | UpdateShortLinkRequest | number,
  signal?: AbortSignal,
): Promise<ShortLinkOpenApiResult> {
  const id = operation === 'delete' ? payload as number : null
  const response = await fetch(operation === 'delete' ? `/v1/short-links/${id}` : '/v1/short-links', {
    method: { create: 'POST', list: 'GET', update: 'PUT', delete: 'DELETE' }[operation],
    cache: 'no-store',
    credentials: 'omit',
    redirect: 'error',
    headers: {
      Authorization: `ApiKey ${apiKey}`,
      ...(operation === 'create' || operation === 'update' ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(operation === 'create' || operation === 'update' ? { body: JSON.stringify(payload) } : {}),
    signal,
  })
  return { status: response.status, body: await response.json() as ShortLinkOpenApiResult['body'] }
}

export async function listManagedShortLinks(apiKeyId: number, query: ListPageQuery = {}): Promise<ListPage<ShortLink>> {
  const response = await http.get<ApiResponse<ListPage<ShortLink>>>('/short-links', { params: { apiKeyId, ...query } })
  return response.data.data
}

export async function updateManagedShortLink(request: UpdateShortLinkRequest): Promise<ShortLink> {
  const response = await http.put<ApiResponse<ShortLink>>('/short-links', request)
  return response.data.data
}

export async function deleteManagedShortLink(id: number): Promise<void> {
  await http.delete(`/short-links/${id}`)
}

export function shortLinkManagementError(error: unknown, fallback: string): string {
  if (!isAxiosError<ApiResponse<unknown>>(error)) return fallback
  if (!error.response) return '无法连接服务器，请确认后端已启动'
  switch (error.response.data?.code) {
    case 'INVALID_ACCESS_TOKEN': return '登录已过期，请重新登录'
    case 'AUTH_ACCOUNT_FORBIDDEN': return '账号已被禁用，无法管理短链接'
    case 'SHORT_LINK_NOT_FOUND': return '短链接或所属 API Key 不存在，或你没有访问权限'
    case 'INVALID_LIST_PAGE': return error.response.data.message || '页码或每页条数无效'
    case 'INVALID_SHORT_LINK_REQUEST':
    case 'VALIDATION_ERROR': return error.response.data.message || '请检查提交的信息'
    default: return fallback
  }
}
