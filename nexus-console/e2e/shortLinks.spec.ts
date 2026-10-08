import { expect, test } from '@playwright/test'
import { installMockApi, sampleKey, signIn, type ShortLink } from './support/mockApi'

const fakeKey = 'PLAYWRIGHT_ONLY_SHORT_LINK_KEY'

test('Key 创建后可复制短地址，JWT 管理页可找回、改名、禁用与删除', async ({ page }) => {
  const mock = await installMockApi(page)
  await signIn(page)
  let createCalls = 0
  await page.route('**/v1/short-links', async (route) => {
    const request = route.request()
    expect(request.method()).toBe('POST')
    expect(request.headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(request.headers().authorization).not.toContain('Bearer')
    const body = request.postDataJSON() as Record<string, unknown>
    expect(body.name).toBe('秋季活动视频')
    expect(body.originalUrl).toBe('https://www.douyin.com/video/123456')
    expect(body).not.toHaveProperty('apiKeyId')
    expect(new Date(String(body.expiresAt)).getTime()).toBeGreaterThan(Date.now())
    const created: ShortLink = {
      id: 123, apiKeyId: sampleKey.id, name: String(body.name), originalUrl: String(body.originalUrl),
      shortCode: 'Ab7Kq2', status: 0, expiresAt: String(body.expiresAt),
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    }
    mock.shortLinks.push(created)
    createCalls++
    await route.fulfill({ status: 200, json: { code: 'SUCCESS', message: '短链接创建成功', data: created } })
  })

  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '短链接', exact: true }).click()
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByLabel('短链名称').fill('秋季活动视频')
  await page.getByLabel('原始 HTTPS 地址').fill('https://www.douyin.com/video/123456')
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '有效期' }) }).click()
  await page.getByRole('option', { name: '1 天' }).click()
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.getByRole('heading', { name: '短链接已创建' })).toBeVisible()
  await expect(page.locator('.playground-created-link code')).toContainText('/s/Ab7Kq2')
  expect(createCalls).toBe(1)
  expect(await page.evaluate((key) => JSON.stringify({ ...localStorage, ...sessionStorage }).includes(key), fakeKey)).toBe(false)
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: '复制短地址' }).click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('http://127.0.0.1:4173/s/Ab7Kq2')

  await page.getByRole('link', { name: '管理这把 Key 的短链' }).click()
  await expect(page).toHaveURL(/\/short-links\?apiKeyId=11$/)
  await expect(page.getByText('秋季活动视频', { exact: true }).first()).toBeVisible()
  await expect(page.locator('.short-links-table')).toContainText('可跳转')
  const managerCalls = mock.calls.filter((call) => call.path === '/api/short-links')
  expect(managerCalls.some((call) => call.method === 'GET')).toBe(true)

  await page.getByRole('button', { name: '改名 秋季活动视频' }).click()
  await page.locator('.el-message-box__input input').fill('新版活动视频')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.locator('.short-links-table')).toContainText('新版活动视频')
  await page.getByRole('button', { name: '禁用 新版活动视频' }).click()
  await page.getByRole('button', { name: '确认禁用' }).click()
  await expect(page.locator('.short-links-table')).toContainText('已禁用')
  await page.getByRole('button', { name: '删除 新版活动视频' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByRole('heading', { name: '还没有短链接' })).toBeVisible()
  expect(mock.shortLinks).toHaveLength(0)
})

test('目标主机不在白名单时不发请求，短链文档可进入调试台', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  let requestCount = 0
  await page.route('**/v1/short-links', async (route) => { requestCount++; await route.abort() })
  await page.getByRole('link', { name: '开发文档' }).click()
  await page.getByRole('complementary', { name: '文档目录' }).getByRole('link', { name: '短链接' }).click()
  await expect(page.locator('.docs-markdown')).toContainText('www.douyin.com')
  await expect(page.locator('.docs-markdown')).toContainText('GET /s/{shortCode}')
  await page.getByRole('link', { name: '在线试用此接口' }).click()
  await expect(page).toHaveURL(/\/playground\?endpoint=shortlink$/)
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByLabel('短链名称').fill('不允许的目标')
  await page.getByLabel('原始 HTTPS 地址').fill('https://evil.example/path')
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.locator('.playground-form-error')).toContainText('精确主机名')
  expect(requestCount).toBe(0)
})

test('自定义到期时刻按浏览器本地时间转换为绝对时间', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  const expected = await page.evaluate(() => new Date('2030-01-01T08:30').toISOString())
  let expiresAt = ''
  await page.route('**/v1/short-links', async (route) => {
    expiresAt = String(route.request().postDataJSON().expiresAt)
    await route.fulfill({ status: 200, json: { code: 'SUCCESS', message: '短链接创建成功', data: {
      id: 123, apiKeyId: sampleKey.id, name: '自定义到期', originalUrl: 'https://weibo.com/1',
      shortCode: 'Custom1', status: 0, expiresAt,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    } } })
  })
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '短链接', exact: true }).click()
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByLabel('短链名称').fill('自定义到期')
  await page.getByLabel('原始 HTTPS 地址').fill('https://weibo.com/1')
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '有效期' }) }).click()
  await page.getByRole('option', { name: '自定义到期时刻' }).click()
  await page.getByLabel('到期时刻（本地时间）').fill('2030-01-01T08:30')
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.getByRole('heading', { name: '短链接已创建' })).toBeVisible()
  expect(expiresAt).toBe(expected)
})

test('调试台的查询、修改和删除都只携带机器 Key', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  const methods: string[] = []
  await page.route('**/v1/short-links**', async (route) => {
    const request = route.request()
    methods.push(request.method())
    expect(request.headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(request.headers().authorization).not.toContain('Bearer')
    if (request.method() === 'GET') {
      await route.fulfill({ status: 200, json: { code: 'SUCCESS', message: '查询成功', data: [{
        id: 123, apiKeyId: sampleKey.id, name: '列表里的短链', originalUrl: 'https://weibo.com/1',
        shortCode: 'DemoList1', status: 0, expiresAt: '2030-01-01T00:00:00Z',
        createdAt: '2026-10-05T10:00:00Z', updatedAt: '2026-10-05T10:00:00Z',
      }] } })
    } else if (request.method() === 'PUT') {
      expect(request.postDataJSON()).toEqual({ id: 123, name: '新名称', status: 1 })
      await route.fulfill({ status: 200, json: { code: 'SUCCESS', message: '更新成功', data: { id: 123, name: '新名称', status: 1 } } })
    } else {
      expect(request.url()).toContain('/v1/short-links/123')
      await route.fulfill({ status: 200, json: { code: 'SUCCESS', message: '删除成功', data: null } })
    }
  })

  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '短链接', exact: true }).click()
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByRole('button', { name: '查询列表' }).click()
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.locator('.playground-link-item')).toContainText('列表里的短链')
  await expect(page.getByRole('link', { name: '打开短地址' })).toHaveAttribute('href', 'http://127.0.0.1:4173/s/DemoList1')
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.locator('.playground-link-item').getByRole('button', { name: '复制短地址' }).click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('http://127.0.0.1:4173/s/DemoList1')
  await page.getByRole('button', { name: '修改短链' }).click()
  await page.getByLabel('短链 ID').fill('123')
  await page.getByLabel('新名称（可选）').fill('新名称')
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '新状态（可选）' }) }).click()
  await page.getByRole('option', { name: '禁用' }).click()
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.locator('.playground-response .playground-code')).toContainText('新名称')
  await page.getByRole('button', { name: '删除短链' }).click()
  await page.getByLabel('短链 ID').fill('123')
  await page.getByRole('button', { name: '发送请求' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.locator('.playground-response .playground-code')).toContainText('删除成功')
  expect(methods).toEqual(['GET', 'PUT', 'DELETE'])
})

test('手机管理页按 Key 隔离列表，禁用的上层资源显示独立状态', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installMockApi(page, {
    applications: [{ id: 1, name: '示例应用', status: 1, createdAt: '2026-10-05T10:00:00', updatedAt: '2026-10-05T10:00:00' }],
    keys: [sampleKey, { ...sampleKey, id: 12, name: '另一把 Key', keyPreview: 'nexus_••••9876' }],
    shortLinks: [
      { id: 1, apiKeyId: 11, name: '第一把 Key 的短链', originalUrl: 'https://weibo.com/1', shortCode: 'OnlyA1', status: 0, expiresAt: '2030-01-01T00:00:00Z', createdAt: '2026-10-05T10:00:00Z', updatedAt: '2026-10-05T10:00:00Z' },
      { id: 2, apiKeyId: 12, name: '第二把 Key 的短链', originalUrl: 'https://weibo.com/2', shortCode: 'OnlyB2', status: 0, expiresAt: '2030-01-01T00:00:00Z', createdAt: '2026-10-05T10:00:00Z', updatedAt: '2026-10-05T10:00:00Z' },
    ],
  })
  await signIn(page)
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.getByRole('link', { name: '短链接', exact: true }).click()
  await expect(page.locator('.short-links-mobile-list')).toContainText('第一把 Key 的短链')
  await expect(page.locator('.short-links-mobile-list')).not.toContainText('第二把 Key 的短链')
  await expect(page.locator('.short-links-mobile-list')).toContainText('上层已禁用')
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: 'API Key', exact: true }) }).click()
  await page.getByRole('option', { name: '另一把 Key · nexus_••••9876' }).click()
  await expect(page.locator('.short-links-mobile-list')).toContainText('第二把 Key 的短链')
  await expect(page.locator('.short-links-mobile-list')).not.toContainText('第一把 Key 的短链')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
