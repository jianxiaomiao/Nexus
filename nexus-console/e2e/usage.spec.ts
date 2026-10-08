import { expect, test, type Page } from '@playwright/test'
import { installMockApi, oneTimeKey, openApplication, sampleApplication, sampleKey, sampleUsage } from './support/mockApi'

async function openKeyDetail(page: Page) {
  await openApplication(page)
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page).toHaveURL(/\/applications\/1\/keys\/11$/)
}

test('Key 详情三个标签保留密钥信息和短链接入口，统计只发 Bearer GET 查询', async ({ page }) => {
  const api = await installMockApi(page)
  await openKeyDetail(page)

  const tabs = page.getByRole('navigation', { name: 'API Key 详情分区' })
  await expect(tabs.getByRole('link')).toHaveCount(3)
  await expect(page.getByText('test-public-id')).toBeVisible()
  await tabs.getByRole('link', { name: '短链接' }).click()
  await expect(page.getByRole('link', { name: '查看短链接' })).toBeVisible()
  await expect(page.getByText('test-public-id')).not.toBeVisible()
  await tabs.getByRole('link', { name: '调用统计' }).click()

  await expect(page.getByText('累计调用')).toBeVisible()
  await expect(page.getByText('15', { exact: true })).toBeVisible()
  await expect(page.getByText('近7天调用')).toBeVisible()
  await expect(page.getByRole('heading', { name: '每日调用次数' })).toBeVisible()
  await expect(page.getByRole('listitem', { name: '2026-10-06，2 次调用' })).toBeVisible()
  await expect(page.locator('.api-row')).toHaveCount(2)
  await expect(page.locator('.api-row').first()).toContainText('uuid.generate')
  await expect(page.locator('body')).not.toContainText(oneTimeKey)

  const request = api.calls.find((call) => call.path === '/api/usage')
  expect(request).toMatchObject({
    method: 'GET', body: {},
    query: { applicationId: '1', apiKeyId: '11', timeRange: 'LAST_7_DAYS' },
  })
})

test('自定义时间以北京时间墙上时间发送，错误区间阻止请求', async ({ page }) => {
  const api = await installMockApi(page)
  await openKeyDetail(page)
  await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '调用统计' }).click()
  await expect(page.getByText('15', { exact: true })).toBeVisible()
  await page.locator('.range-select').click()
  await page.getByRole('option', { name: '自定义' }).click()

  await page.getByLabel('开始时间（北京时间）').fill('2026-10-09T00:00')
  await page.getByLabel('结束时间（北京时间，不含）').fill('2026-10-08T00:00')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.locator('.usage-notice.is-error')).toContainText('结束时间必须晚于开始时间')
  expect(api.calls.filter((call) => call.path === '/api/usage')).toHaveLength(1)

  await page.getByLabel('开始时间（北京时间）').fill('2026-10-08T00:00')
  await page.getByLabel('结束时间（北京时间，不含）').fill('2026-10-09T00:00')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('15', { exact: true })).toBeVisible()
  expect(api.calls.filter((call) => call.path === '/api/usage').at(-1)?.query).toEqual({
    applicationId: '1', apiKeyId: '11', timeRange: 'CUSTOM',
    customStartTime: '2026-10-08T00:00:00', customEndTime: '2026-10-09T00:00:00',
  })
})

test('禁用的 Key 仍可查看历史；所选时段无记录有清楚空状态', async ({ page }) => {
  await installMockApi(page, {
    applications: [{ ...sampleApplication, status: 1 }],
    keys: [{ ...sampleKey, status: 1 }],
    usage: { ...sampleUsage, periodCount: 0, dailyCounts: sampleUsage.dailyCounts.map((day) => ({ ...day, count: 0 })), apiCounts: [] },
  })
  await openKeyDetail(page)
  await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '调用统计' }).click()
  await expect(page.getByText('累计调用')).toBeVisible()
  await expect(page.getByText('15', { exact: true })).toBeVisible()
  await expect(page.getByText('该时段暂无调用记录')).toBeVisible()
  await expect(page.getByText('该时段暂无接口调用')).toBeVisible()
})

test('统计加载失败可在原位重试', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('GET /api/usage', { status: 503, code: 'TEMPORARY_ERROR', message: '暂时失败' })
  await openKeyDetail(page)
  await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '调用统计' }).click()
  await expect(page.locator('.usage-notice.is-error')).toContainText('加载调用统计失败')
  api.failures.delete('GET /api/usage')
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByText('15', { exact: true })).toBeVisible()
})

test('手机宽度下统计页不撑破页面，接口记录仍可阅读', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installMockApi(page)
  await openKeyDetail(page)
  await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '调用统计' }).click()
  await expect(page.locator('.api-row')).toHaveCount(2)
  await expect(page.locator('.api-row').first()).toContainText('uuid.generate')
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})
