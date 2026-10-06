import { expect, type Page } from '@playwright/test'

export type Application = {
  id: number
  name: string
  status: 0 | 1
  createdAt: string
  updatedAt: string
}

export type ApiKey = {
  id: number
  applicationId: number
  name: string
  publicId: string
  keyPreview: string
  status: 0 | 1
  createdAt: string
  updatedAt: string
}

type Failure = { status: number; code: string; message: string }
type RecordedRequest = { method: string; path: string; body: Record<string, unknown> }

const timestamp = '2026-10-05T10:00:00'
export const sampleApplication: Application = { id: 1, name: '示例应用', status: 0, createdAt: timestamp, updatedAt: timestamp }
export const sampleKey: ApiKey = {
  id: 11, applicationId: 1, name: '开发环境 Key', publicId: 'test-public-id',
  keyPreview: 'nexus_••••1234', status: 0, createdAt: timestamp, updatedAt: timestamp,
}
// 故意不是可用凭证；只用于检查创建后的一次性展示。
export const oneTimeKey = 'PLAYWRIGHT_FAKE_KEY_NOT_VALID'

export async function installMockApi(page: Page, initial?: { applications?: Application[]; keys?: ApiKey[] }) {
  const applications = structuredClone(initial?.applications ?? [sampleApplication])
  const keys = structuredClone(initial?.keys ?? [sampleKey])
  const calls: RecordedRequest[] = []
  const failures = new Map<string, Failure>()
  let nextApplicationId = 100
  let nextKeyId = 100

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname
    const body = request.postData() ? request.postDataJSON() as Record<string, unknown> : {}
    calls.push({ method, path, body })

    const respond = async (data: unknown, status = 200, code = 'SUCCESS', message = '成功') => {
      await route.fulfill({ status, json: { code, message, data } })
    }
    const failure = failures.get(`${method} ${path}`)
    if (failure) {
      await respond(null, failure.status, failure.code, failure.message)
      return
    }

    if (path === '/api/auth/login' && method === 'POST') {
      await respond({ accessToken: 'playwright-only-token', tokenType: 'Bearer', expiresInSeconds: 1800 })
      return
    }
    if (path === '/api/auth/register' && method === 'POST') {
      await respond({ email: body.email, displayName: body.displayName })
      return
    }

    // 每个管理请求都必须带人类账号的 Bearer 凭证。
    expect(request.headers().authorization).toBe('Bearer playwright-only-token')
    if (path === '/api/application' && method === 'GET') {
      await respond(applications)
    } else if (path === '/api/application/create' && method === 'POST') {
      const created: Application = { id: nextApplicationId++, name: String(body.name), status: 0, createdAt: timestamp, updatedAt: timestamp }
      applications.push(created)
      await respond({ id: created.id, name: created.name })
    } else if (path === '/api/application' && method === 'PUT') {
      const target = applications.find((item) => item.id === body.id)
      if (!target) { await respond(null, 404, 'APPLICATION_NOT_FOUND'); return }
      if (typeof body.name === 'string') target.name = body.name
      if (body.status === 0 || body.status === 1) target.status = body.status
      await respond(target)
    } else if (path.startsWith('/api/application/') && method === 'DELETE') {
      const index = applications.findIndex((item) => item.id === Number(path.split('/').at(-1)))
      if (index < 0) { await respond(null, 404, 'APPLICATION_NOT_FOUND'); return }
      applications.splice(index, 1)
      await respond(null)
    } else if (/^\/api\/apiKey\/\d+$/.test(path) && method === 'GET') {
      const applicationId = Number(path.split('/').at(-1))
      await respond({ apiKeyResponseList: keys.filter((item) => item.applicationId === applicationId) })
    } else if (path === '/api/apiKey/create' && method === 'POST') {
      const created: ApiKey = {
        id: nextKeyId++, applicationId: Number(body.applicationId), name: String(body.name),
        publicId: 'new-test-public-id', keyPreview: 'nexus_••••5678', status: 0,
        createdAt: timestamp, updatedAt: timestamp,
      }
      keys.push(created)
      await respond({ ...created, apiKey: oneTimeKey })
    } else if (path === '/api/apiKey' && method === 'PUT') {
      const target = keys.find((item) => item.applicationId === body.applicationId && item.id === body.apiKeyId)
      if (!target) { await respond(null, 404, 'API_KEY_NOT_FOUND'); return }
      if (typeof body.name === 'string') target.name = body.name
      if (body.status === 0 || body.status === 1) target.status = body.status
      await respond(target)
    } else if (path === '/api/apiKey' && method === 'DELETE') {
      const index = keys.findIndex((item) => item.applicationId === body.applicationId && item.id === body.apiKeyId)
      if (index < 0) { await respond(null, 404, 'API_KEY_NOT_FOUND'); return }
      keys.splice(index, 1)
      await respond(null)
    } else {
      await route.abort()
      throw new Error(`未模拟的 API 请求：${method} ${path}`)
    }
  })

  return { applications, keys, calls, failures }
}

export async function signIn(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入邮箱').fill('learner@example.test')
  await page.getByPlaceholder('请输入密码').fill('example-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('heading', { name: 'Applications', exact: true })).toBeVisible()
}

export async function openApplication(page: Page) {
  await signIn(page)
  await page.getByRole('article').filter({ hasText: sampleApplication.name }).getByRole('link', { name: '查看详情' }).click()
  await expect(page).toHaveURL(/\/applications\/1$/)
}
