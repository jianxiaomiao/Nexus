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

export type ScreenshotJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED'

export interface CreatedScreenshotJob {
  jobId: string
  status: ScreenshotJobStatus
  attemptCount: number
  createdAt: string
  updatedAt: string
}

export interface ScreenshotJob extends CreatedScreenshotJob {
  resultSizeBytes: number | null
  resultSha256: string | null
  blockedResources: number | null
  resourceLimitReached: boolean | null
  resultExpiresAt: string | null
  failureCode: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface ScreenshotJobApiResult<T> extends OpenApiResult {
  data: T | null
}

const screenshotJobStatuses = new Set<ScreenshotJobStatus>(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'])

function isScreenshotJobStatus(value: unknown): value is ScreenshotJobStatus {
  return typeof value === 'string' && screenshotJobStatuses.has(value as ScreenshotJobStatus)
}

function isCreatedScreenshotJob(value: unknown): value is CreatedScreenshotJob {
  if (!value || typeof value !== 'object') return false
  const job = value as Record<string, unknown>
  return typeof job.jobId === 'string' && job.jobId.length > 0
    && isScreenshotJobStatus(job.status)
    && Number.isSafeInteger(job.attemptCount) && Number(job.attemptCount) >= 0
    && typeof job.createdAt === 'string' && typeof job.updatedAt === 'string'
}

function nullableNumber(value: unknown): boolean {
  return value === null || (typeof value === 'number' && Number.isFinite(value))
}

function nullableString(value: unknown): boolean {
  return value === null || typeof value === 'string'
}

function isScreenshotJob(value: unknown): value is ScreenshotJob {
  if (!isCreatedScreenshotJob(value)) return false
  const job = value as unknown as Record<string, unknown>
  return nullableNumber(job.resultSizeBytes)
    && nullableString(job.resultSha256)
    && nullableNumber(job.blockedResources)
    && (job.resourceLimitReached === null || typeof job.resourceLimitReached === 'boolean')
    && nullableString(job.resultExpiresAt)
    && nullableString(job.failureCode)
    && nullableString(job.startedAt)
    && nullableString(job.completedAt)
}

async function readJsonResponse(response: Response): Promise<ApiResponse<unknown> | null> {
  const mediaType = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase()
  if (mediaType !== 'application/json') return null
  try { return await response.json() as ApiResponse<unknown> }
  catch { return null }
}

async function readScreenshotResponse(response: Response): Promise<WebScreenshotResult> {
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
    result.body = await readJsonResponse(response)
    result.invalidImage = response.ok
  }
  return result
}

export async function callWebScreenshot(apiKey: string, url: string, signal?: AbortSignal): Promise<WebScreenshotResult> {
  const response = await fetch('/v1/web/screenshot', {
    method: 'POST', cache: 'no-store', credentials: 'omit', redirect: 'error',
    headers: { Authorization: `ApiKey ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }), signal,
  })
  return readScreenshotResponse(response)
}

export async function submitScreenshotJob(
  apiKey: string,
  url: string,
  signal?: AbortSignal,
): Promise<ScreenshotJobApiResult<CreatedScreenshotJob>> {
  const response = await fetch('/v1/web/screenshot-jobs', {
    method: 'POST', cache: 'no-store', credentials: 'omit', redirect: 'error',
    headers: { Authorization: `ApiKey ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }), signal,
  })
  const body = await readJsonResponse(response)
  return {
    status: response.status,
    body,
    data: isCreatedScreenshotJob(body?.data) ? body.data : null,
  }
}

export async function getScreenshotJob(
  apiKey: string,
  jobId: string,
  signal?: AbortSignal,
): Promise<ScreenshotJobApiResult<ScreenshotJob>> {
  const response = await fetch(`/v1/web/screenshot-jobs/${encodeURIComponent(jobId)}`, {
    method: 'GET', cache: 'no-store', credentials: 'omit', redirect: 'error',
    headers: { Authorization: `ApiKey ${apiKey}` }, signal,
  })
  const body = await readJsonResponse(response)
  return {
    status: response.status,
    body,
    data: isScreenshotJob(body?.data) ? body.data : null,
  }
}

export async function getScreenshotJobResult(
  apiKey: string,
  jobId: string,
  signal?: AbortSignal,
): Promise<WebScreenshotResult> {
  const response = await fetch(`/v1/web/screenshot-jobs/${encodeURIComponent(jobId)}/result`, {
    method: 'GET', cache: 'no-store', credentials: 'omit', redirect: 'error',
    headers: { Authorization: `ApiKey ${apiKey}` }, signal,
  })
  return readScreenshotResponse(response)
}
