import { expect, test } from '@playwright/test'
import { installMockApi, sampleKey, signIn } from './support/mockApi'

for (const timezoneId of ['UTC', 'Asia/Shanghai', 'America/Los_Angeles']) {
  test(`Key、短链与 Webhook 在 ${timezoneId} 设备按北京时间跨日展示`, async ({ page }) => {
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Emulation.setTimezoneOverride', { timezoneId })
    const utc = '2026-11-08T20:30:00Z'
    await installMockApi(page, {
      keys: [{ ...sampleKey, createdAt: utc, updatedAt: utc }],
      shortLinks: [{ id: 9, apiKeyId: 11, name: 'UTC link', originalUrl: 'https://weibo.com/1',
        shortCode: 'UtcLink', status: 0, createdAt: utc, updatedAt: utc, expiresAt: '2030-01-01T20:30:00Z' }],
      // This verifies the frontend contract only; backend Webhook management is a separate task.
      webhooks: [{ id: 21, applicationId: 1, url: 'https://example.com/hook', status: 0,
        currentSecretVersion: 1, createdAt: utc, updatedAt: utc }],
    })
    await signIn(page)
    await page.getByRole('article').getByRole('link', { name: '查看详情' }).click()
    await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
    await expect(page.locator('.el-table__body')).toContainText('2026年11月9日')
    await page.getByRole('link', { name: '查看详情' }).click()
    await expect(page.locator('dl')).toContainText('2026年11月9日 04:30')
    await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '短链接' }).click()
    await page.getByRole('link', { name: '查看短链接 →' }).click()
    await expect(page.locator('.short-links-table')).toContainText('2030年1月2日 04:30')
    await expect(page.locator('.short-links-table')).toContainText('2026年11月9日 04:30')
    await page.locator('a[href="/applications"]').first().click()
    await page.getByRole('article').getByRole('link', { name: '查看详情' }).click()
    await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'Webhook' }).click()
    await expect(page.locator('dl')).toContainText('2026年11月9日 04:30')
  })

  test(`截图任务在 ${timezoneId} 设备按北京时间展示创建与过期时刻`, async ({ page }) => {
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Emulation.setTimezoneOverride', { timezoneId })
    await installMockApi(page)
    const job = { jobId: 'utc-time-job', status: 'EXPIRED', attemptCount: 1,
      createdAt: '2026-11-08T20:30:00Z', updatedAt: '2026-11-08T21:30:00Z',
      startedAt: '2026-11-08T20:30:00Z', completedAt: '2026-11-08T20:30:01Z',
      resultExpiresAt: '2026-11-08T21:30:00Z', failureCode: null,
      resultSizeBytes: 10, resultSha256: 'test-only', blockedResources: 0, resourceLimitReached: false }
    await page.route('**/v1/web/screenshot-jobs', route => route.fulfill({ status: 202,
      json: { code: 'SUCCESS', data: { ...job, status: 'QUEUED', attemptCount: 0 } } }))
    await page.route('**/v1/web/screenshot-jobs/utc-time-job', route => route.fulfill({
      json: { code: 'SUCCESS', data: job } }))
    await signIn(page)
    await page.getByRole('link', { name: 'API 调试台' }).click()
    await page.getByRole('button', { name: '网页截图', exact: true }).click()
    await page.getByLabel('API Key', { exact: true }).fill('PLAYWRIGHT_ONLY_TIME_KEY')
    await page.getByLabel('网页 URL', { exact: true }).fill('https://blog.csdn.net/test/article/details/123')
    await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
    await expect(page.locator('.playground-job-details')).toContainText('2026年11月9日 04:30')
    await expect(page.locator('.playground-job-details')).toContainText('2026年11月9日 05:30')
  })
}
