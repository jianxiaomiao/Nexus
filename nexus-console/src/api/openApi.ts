import type { ApiResponse } from './http'

export type OpenApiEndpoint = 'uuid' | 'hash'
export type HashAlgorithm = 'SHA256' | 'SHA512'

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
