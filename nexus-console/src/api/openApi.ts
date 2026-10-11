import type { ApiResponse } from './http'

export type OpenApiEndpoint = 'uuid' | 'hash'
export type HashAlgorithm = 'SHA256' | 'SHA512'

export interface WebExtractResponse {
  title: string
  textContent: string
  contentHtml: string
}

export function isWebExtractResponse(data: unknown): data is WebExtractResponse {
  if (!data || typeof data !== 'object') return false
  const value = data as Record<string, unknown>
  return typeof value.title === 'string' && typeof value.textContent === 'string' && typeof value.contentHtml === 'string'
}

export interface OpenApiResult {
  status: number
  body: ApiResponse<unknown> | null
}

// 机器接口与管理端 /api 客户端隔离，绝不自动附加用户 Bearer JWT。
export async function callOpenApi(
  endpoint: OpenApiEndpoint,
  apiKey: string,
  algorithm: HashAlgorithm,
  value: string,
  signal?: AbortSignal,
): Promise<OpenApiResult> {
  const isHash = endpoint === 'hash'
  const response = await fetch(isHash ? '/v1/utils/hash' : '/v1/utils/uuid', {
    method: isHash ? 'POST' : 'GET',
    cache: 'no-store',
    credentials: 'omit',
    redirect: 'error',
    headers: {
      Authorization: `ApiKey ${apiKey}`,
      ...(isHash ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(isHash ? { body: JSON.stringify({ algorithm, value }) } : {}),
    signal,
  })

  // 认证失败也需要把真实 HTTP 状态和后端错误体展示在响应区。
  const body = await response.json() as ApiResponse<unknown>
  return { status: response.status, body }
}

export async function callWebExtract(apiKey: string, url: string, signal?: AbortSignal): Promise<OpenApiResult> {
  const response = await fetch('/v1/web/extract', {
    method: 'POST',
    cache: 'no-store',
    credentials: 'omit',
    redirect: 'error',
    headers: { Authorization: `ApiKey ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
    signal,
  })
  return { status: response.status, body: await response.json() as ApiResponse<unknown> }
}

export interface WebScreenshotResult extends OpenApiResult {
  png: Blob | null
  blockedResources: number | null
  resourceLimitReached: boolean
  invalidImage: boolean
  cacheControl: string | null
}

export async function callWebScreenshot(apiKey: string, url: string, signal?: AbortSignal): Promise<WebScreenshotResult> {
  const response = await fetch('/v1/web/screenshot', {
    method: 'POST', cache: 'no-store', credentials: 'omit', redirect: 'error',
    headers: { Authorization: `ApiKey ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }), signal,
  })
  const mediaType = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase()
  const rawBlocked = response.headers.get('X-Nexus-Blocked-Resources')
  const blocked = rawBlocked !== null && /^\d+$/.test(rawBlocked) ? Number(rawBlocked) : null
  const result: WebScreenshotResult = {
    status: response.status, body: null, png: null,
    blockedResources: blocked !== null && Number.isSafeInteger(blocked) ? blocked : null,
    resourceLimitReached: response.headers.get('X-Nexus-Resource-Limit-Reached')?.toLowerCase() === 'true',
    invalidImage: false,
    cacheControl: response.headers.get('Cache-Control'),
  }
  if (response.ok && mediaType === 'image/png') {
    const png = await response.blob()
    const signature = new Uint8Array(await png.slice(0, 8).arrayBuffer())
    const expected = [137, 80, 78, 71, 13, 10, 26, 10]
    if (png.size <= 5 * 1024 * 1024 && signature.length === 8 && signature.every((value, index) => value === expected[index])) {
      result.png = png
    } else { result.invalidImage = true }
  } else {
    // 不将 HTML/SVG 或其他类型的成功响应当作可执行网页显示。
    if (mediaType === 'application/json') {
      try { result.body = await response.json() as ApiResponse<unknown> }
      catch { /* 非法 JSON 仍保留真实 HTTP 状态，不伪装成网络失败。 */ }
    }
    result.invalidImage = response.ok
  }
  return result
}
