import { expect, test } from '@playwright/test'
import { installMockApi, signIn } from './support/mockApi'

const fakeKey = 'PLAYWRIGHT_ONLY_MACHINE_KEY'

test('Hash 调试只发送机器 API Key，并展示真实成功结果', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  let requestCount = 0
  await page.route('**/v1/utils/hash', async (route) => {
    requestCount++
    expect(route.request().headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(route.request().headers().authorization).not.toContain('Bearer')
    expect(route.request().postDataJSON()).toEqual({ algorithm: 'SHA512', value: '你好' })
    await route.fulfill({ status: 200, json: { code: 'SUCCESS', message: 'hash摘要成功', data: { hashContent: 'example-digest' } } })
  })

  await page.getByRole('link', { name: 'API 调试台' }).click()
  await expect(page).toHaveURL(/\/playground$/)
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByLabel('算法').selectOption('SHA512')
  await page.getByLabel('待计算的内容').fill('你好')
  await expect(page.locator('#playground-byte-count')).toContainText('6 / 4096 字节')
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.getByRole('heading', { name: '响应' }).locator('..')).toContainText('200 OK')
  await expect(page.locator('.playground-response .playground-code')).toContainText('example-digest')
  expect(requestCount).toBe(1)

  await page.getByRole('button', { name: 'PowerShell' }).click()
  await expect(page.locator('.playground-example-code')).toContainText('ApiKey $apiKey')
  await expect(page.locator('.playground-example-code')).not.toContainText(fakeKey)
  await expect(page.locator('.playground-response')).not.toContainText(fakeKey)
  expect(await page.evaluate((key) => JSON.stringify({ ...localStorage, ...sessionStorage }).includes(key), fakeKey)).toBe(false)
})

test('认证失败展示 401/403，不清除管理端登录；字节超限不发送', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  let status = 401
  let requestCount = 0
  await page.route('**/v1/utils/hash', async (route) => {
    requestCount++
    await route.fulfill({ status, json: {
      code: status === 401 ? 'INVALID_API_KEY_CREDENTIAL' : 'API_KEY_FORBIDDEN',
      message: status === 401 ? 'API Key 无效' : 'API Key 已禁用', data: null,
    } })
  })

  await page.getByRole('link', { name: 'API 调试台' }).click()
  await expect(page).toHaveURL(/\/playground$/)
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.locator('.playground-status')).toContainText('401')
  await expect(page.locator('.playground-response')).toContainText('INVALID_API_KEY_CREDENTIAL')

  status = 403
  await page.getByRole('button', { name: '发送请求' }).click()
  await expect(page.locator('.playground-status')).toContainText('403')
  await expect(page.locator('.playground-response')).toContainText('API_KEY_FORBIDDEN')

  await page.getByLabel('待计算的内容').fill('中'.repeat(1366))
  await expect(page.locator('#playground-byte-count')).toContainText('4098 / 4096 字节')
  await expect(page.getByRole('button', { name: '发送请求' })).toBeDisabled()
  expect(requestCount).toBe(2)
  await page.getByRole('link', { name: 'Applications' }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('heading', { name: 'Applications', exact: true })).toBeVisible()
})

test('文档跳转预选 UUID，窄屏保持纵向布局且离开后清除 Key', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.getByRole('link', { name: '开发文档' }).click()
  await page.getByRole('button', { name: '打开文档目录' }).click()
  await page.getByRole('complementary', { name: '文档目录' }).getByRole('link', { name: '生成 UUID' }).click()
  await page.getByRole('link', { name: '在线试用此接口' }).click()
  await expect(page).toHaveURL(/\/playground\?endpoint=uuid$/)
  await expect(page.getByRole('button', { name: '生成 UUID' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByLabel('待计算的内容')).toHaveCount(0)
  await expect(page.locator('.playground-decoration')).toBeVisible()
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('link', { name: '查看接口文档' }).click()
  await page.getByRole('link', { name: '打开 API 调试台' }).click()
  await expect(page.getByLabel('API Key', { exact: true })).toHaveValue('')
})
