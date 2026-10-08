import { expect, test } from '@playwright/test'
import { installMockApi, openApplication, sampleApplication, sampleKey, signIn, type Application, type ApiKey, type ShortLink } from './support/mockApi'

const applications: Application[] = [sampleApplication, ...Array.from({ length: 10 }, (_, index) => ({
  ...sampleApplication, id: index + 2, name: `应用-${index + 2}`,
}))]
const keys: ApiKey[] = [sampleKey, ...Array.from({ length: 10 }, (_, index) => ({
  ...sampleKey, id: index + 12, name: `Key-${index + 12}`,
}))]
const links: ShortLink[] = Array.from({ length: 11 }, (_, index) => ({
  id: index + 1, apiKeyId: sampleKey.id, name: `短链-${index + 1}`,
  originalUrl: `https://weibo.com/${index + 1}`, shortCode: `Page${index + 1}`,
  status: 0, expiresAt: '2030-01-01T00:00:00Z',
  createdAt: '2026-10-05T10:00:00Z', updatedAt: '2026-10-05T10:00:00Z',
}))

test('Application 第11条可翻页并直达详情', async ({ page }) => {
  const api = await installMockApi(page, { applications })
  await signIn(page)
  await expect(page.locator('.application-card:not(.skeleton-card)')).toHaveCount(10)
  await page.locator('.list-pagination .btn-next').click()
  await expect(page.getByRole('heading', { name: '应用-11' })).toBeVisible()
  await page.getByRole('article').filter({ hasText: '应用-11' }).getByRole('link', { name: '查看详情' }).click()
  await expect(page).toHaveURL(/\/applications\/11$/)
  await expect(page.getByRole('heading', { name: '应用-11' })).toBeVisible()
  expect(api.calls.some((call) => call.path === '/api/application' && call.query.current === '2')).toBeTruthy()
  expect(api.calls.some((call) => call.path === '/api/application' && call.query.applicationId === '11')).toBeTruthy()
  await page.getByRole('link', { name: '返回 Applications' }).click()
  await page.getByRole('spinbutton', { name: '每页条数' }).fill('5')
  await page.getByRole('spinbutton', { name: '每页条数' }).blur()
  await expect(page.locator('.application-card:not(.skeleton-card)')).toHaveCount(5)
  expect(api.calls.filter((call) => call.path === '/api/application').at(-1)?.query).toMatchObject({ current: '1', size: '5' })
})

test('API Key 第11条可翻页并直达详情', async ({ page }) => {
  const api = await installMockApi(page, { keys })
  await openApplication(page)
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await expect(page.locator('.key-table .el-table__row')).toHaveCount(10)
  await page.locator('.list-pagination .btn-next').click()
  await expect(page.locator('.key-table')).toContainText('Key-21')
  await page.locator('.key-table').getByRole('link', { name: '查看详情' }).click()
  await expect(page).toHaveURL(/\/applications\/1\/keys\/21$/)
  await expect(page.getByRole('heading', { name: 'Key-21' })).toBeVisible()
  expect(api.calls.some((call) => call.path === '/api/apiKey/1' && call.query.current === '2')).toBeTruthy()
  expect(api.calls.some((call) => call.path === '/api/apiKey/1' && call.query.apiKeyId === '21')).toBeTruthy()
  await page.getByRole('link', { name: '返回 API Keys' }).click()
  await page.getByRole('spinbutton', { name: '每页条数' }).fill('5')
  await page.getByRole('spinbutton', { name: '每页条数' }).blur()
  await expect(page.locator('.key-table .el-table__row')).toHaveCount(5)
  expect(api.calls.filter((call) => call.path === '/api/apiKey/1').at(-1)?.query).toMatchObject({ current: '1', size: '5' })
})

test('短链接第11条可翻页，仍只查询当前 Key', async ({ page }) => {
  const api = await installMockApi(page, { shortLinks: links })
  await openApplication(page)
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '短链接' }).click()
  await page.getByRole('link', { name: '查看短链接' }).click()
  await expect(page.locator('.short-links-table tbody tr')).toHaveCount(10)
  await page.locator('.list-pagination .btn-next').click()
  await expect(page.locator('.short-links-table tbody tr')).toHaveCount(1)
  await expect(page.locator('.short-links-table')).toContainText('短链-11')
  expect(api.calls.filter((call) => call.path === '/api/short-links' && call.method === 'GET').at(-1)?.query)
    .toMatchObject({ apiKeyId: '11', current: '2', size: '10' })
  await page.getByRole('spinbutton', { name: '每页条数' }).fill('5')
  await page.getByRole('spinbutton', { name: '每页条数' }).blur()
  await expect(page.locator('.short-links-table tbody tr')).toHaveCount(5)
  expect(api.calls.filter((call) => call.path === '/api/short-links' && call.method === 'GET').at(-1)?.query)
    .toMatchObject({ apiKeyId: '11', current: '1', size: '5' })
})
