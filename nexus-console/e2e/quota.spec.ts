import { expect, test } from '@playwright/test'
import { installMockApi, openApplication, sampleApplication, sampleQuota, signIn } from './support/mockApi'

test('列表直接显示本页各应用的配额，不逐卡查询，禁用与耗尽分别显示', async ({ page }) => {
  const api = await installMockApi(page, { applications: [
    { ...sampleApplication, name: '晨星实验室' },
    { ...sampleApplication, id: 2, name: '小岛计划', status: 1, quota: { ...sampleQuota, usedCount: 920, restCount: 80, periodEnd: '2026-11-14T09:30:00' } },
    { ...sampleApplication, id: 3, name: 'Nexus Demo', quota: { ...sampleQuota, usedCount: 1000, restCount: 0 } },
  ] })
  await signIn(page)
  const cards = page.getByRole('article')
  await expect(cards).toHaveCount(3)
  await expect(cards.nth(0).locator('.quota-amount')).toHaveText('350 / 1000 次')
  await expect(cards.nth(1)).toContainText('已禁用')
  await expect(cards.nth(1)).toContainText('接近上限')
  await expect(cards.nth(1).locator('.quota-recovery')).toContainText('2026年11月14日 09:30')
  await expect(cards.nth(2)).toContainText('运行中')
  await expect(cards.nth(2)).toContainText('额度已用尽')
  expect(api.calls.filter((call) => call.path.endsWith('/quota'))).toHaveLength(0)
})

test('详情可单独刷新配额，查询失败显示错误，重试后显示跨周期的新值', async ({ page }) => {
  const api = await installMockApi(page)
  await openApplication(page)
  const card = page.getByRole('region', { name: '当前 30 天配额' })
  await expect(card.locator('.quota-amount')).toHaveText('350 / 1000 次')
  api.failures.set('GET /api/application/1/quota', { status: 503, code: 'TEMPORARY_ERROR', message: '查询失败' })
  await card.getByRole('button', { name: '刷新配额' }).click()
  await expect(card.getByRole('alert')).toContainText('暂时无法加载配额')
  await expect(card.locator('.quota-amount')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '应用信息' })).toBeVisible()
  api.failures.delete('GET /api/application/1/quota')
  api.applications[0]!.quota = { ...sampleQuota, periodStart: sampleQuota.periodEnd, periodEnd: '2026-12-12T14:00:00', usedCount: 0, restCount: 1000 }
  await card.getByRole('button', { name: '重试配额查询' }).click()
  await expect(card.locator('.quota-amount')).toHaveText('0 / 1000 次')
  await expect(card.locator('.quota-period')).toContainText('2026年12月12日 14:00')
  expect(api.calls.filter((call) => call.path === '/api/application' && call.query.applicationId)).toHaveLength(1)
  expect(api.calls.filter((call) => call.path.endsWith('/quota'))).toHaveLength(2)
})

test('详情刷新等待时显示局部加载，重复点击只发送一次查询', async ({ page }) => {
  await installMockApi(page)
  await openApplication(page)
  let finish!: () => void
  const pending = new Promise<void>((resolve) => { finish = resolve })
  let count = 0
  await page.route('**/api/application/1/quota', async (route) => {
    count++
    await pending
    await route.fulfill({ json: { code: 'SUCCESS', message: '成功', data: { ...sampleQuota, usedCount: 351, restCount: 649 } } })
  })
  const card = page.locator('.quota-card')
  await card.getByRole('button', { name: '刷新配额' }).click()
  await expect(card).toHaveAttribute('aria-busy', 'true')
  await expect(card.getByRole('button', { name: '刷新配额' })).toBeDisabled()
  finish()
  await expect(card.locator('.quota-amount')).toHaveText('351 / 1000 次')
  expect(count).toBe(1)
})

test('更新应用信息的响应不含配额，配额显示仍然保留', async ({ page }) => {
  await installMockApi(page)
  await openApplication(page)
  await page.getByRole('button', { name: '编辑应用名称' }).click()
  await page.getByPlaceholder('应用名称').fill('新名称')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByRole('heading', { name: '新名称' })).toBeVisible()
  await expect(page.locator('.quota-card .quota-amount')).toHaveText('350 / 1000 次')
  await page.getByRole('button', { name: '禁用应用' }).click()
  await page.getByRole('button', { name: '确认禁用' }).click()
  await expect(page.getByRole('button', { name: '启用应用' })).toBeVisible()
  await expect(page.locator('.quota-card .quota-remaining strong')).toHaveText('650')
})

test('零上限显示无可用额度，进度不产生 NaN', async ({ page }) => {
  await installMockApi(page, { applications: [{ ...sampleApplication, quota: { ...sampleQuota, quotaLimit: 0, usedCount: 0, restCount: 0 } }] })
  await openApplication(page)
  await expect(page.locator('.quota-card')).toContainText('无可用额度')
  await expect(page.locator('.quota-card .quota-amount')).toHaveText('0 / 0 次')
  await expect(page.locator('.quota-card .el-progress')).toHaveAttribute('aria-valuenow', '0')
  await expect(page.locator('.quota-card')).not.toContainText('NaN')
})

for (const width of [1440, 390]) {
  test(`非北京时间设备下 ${width}px 两页显示北京时间且没有横向溢出`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'America/Los_Angeles' })
    await installMockApi(page)
    await signIn(page)
    await expect(page.locator('.quota-recovery')).toContainText('2026年11月12日 14:00')
    expect(await page.locator('.console-scroll-area > .el-scrollbar__wrap').evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
    await page.screenshot({ path: testInfo.outputPath(`quota-list-${width}.png`), fullPage: true })
    await page.getByRole('link', { name: '查看详情' }).click()
    await expect(page.locator('.quota-period')).toContainText('2026年10月13日 14:00')
    await expect(page.locator('.quota-period')).toContainText('2026年11月12日 14:00')
    expect(await page.locator('.console-scroll-area > .el-scrollbar__wrap').evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
    await page.screenshot({ path: testInfo.outputPath(`quota-detail-${width}.png`), fullPage: true })
  })
}
