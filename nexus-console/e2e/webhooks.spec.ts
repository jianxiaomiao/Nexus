import { expect, test } from '@playwright/test'
import {
  installMockApi,
  oneTimeRotatedWebhookSecret,
  oneTimeWebhookSecret,
  openApplication,
  type ApplicationWebhook,
} from './support/mockApi'

const sampleWebhook: ApplicationWebhook = {
  id: 21,
  applicationId: 1,
  url: 'https://api.example.com/nexus/webhooks',
  status: 0,
  currentSecretVersion: 1,
  createdAt: '2026-11-05T01:30:00Z',
  updatedAt: '2026-11-05T02:15:00Z',
}

async function openWebhookTab(page: Parameters<typeof openApplication>[0]) {
  await openApplication(page)
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'Webhook' }).click()
  await expect(page).toHaveURL(/\/applications\/1\?tab=webhook$/)
}

test('未配置时校验 HTTPS，创建后 Secret 仅显示一次且阻止误离开', async ({ page }) => {
  const api = await installMockApi(page)
  await openWebhookTab(page)
  await expect(page.getByRole('heading', { name: 'Webhook 通知' })).toBeVisible()

  const urlInput = page.getByPlaceholder('https://api.example.com/nexus/webhooks')
  await urlInput.fill('http://127.0.0.1/hook#fragment')
  await page.getByRole('button', { name: '创建 Webhook' }).click()
  await expect(page.getByText('请输入不含账号和 Fragment 的完整 HTTPS 地址')).toBeVisible()
  expect(api.calls.filter((call) => call.method === 'POST' && call.path.endsWith('/webhook'))).toHaveLength(0)

  await urlInput.fill('  https://receiver.example.test/nexus-hook  ')
  await page.getByRole('button', { name: '创建 Webhook' }).click()
  const dialog = page.getByRole('dialog', { name: '保存 Webhook Secret' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('#webhook-secret')).toHaveValue(oneTimeWebhookSecret)
  expect(api.calls.find((call) => call.method === 'POST' && call.path.endsWith('/webhook'))?.body)
    .toEqual({ url: 'https://receiver.example.test/nexus-hook' })

  await expect(dialog.getByRole('button', { name: '完成' })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()

  await dialog.getByText('我已经安全保存这个 Secret').click()
  await dialog.getByRole('button', { name: '完成' }).click()
  await expect(dialog).toHaveCount(0)
  const detailNavigation = page.getByRole('navigation', { name: '应用详情分区' })
  await detailNavigation.getByRole('link', { name: 'API Keys' }).click()
  await detailNavigation.getByRole('link', { name: 'Webhook' }).click()
  await expect(page.getByRole('heading', { name: 'Webhook 配置' })).toBeVisible()
  await expect(page.locator('#webhook-secret')).toHaveCount(0)
})

test('已配置 Webhook 可修改 URL、暂停恢复并轮换 Secret', async ({ page }) => {
  const api = await installMockApi(page, { webhooks: [sampleWebhook] })
  await openWebhookTab(page)
  await expect(page.getByText('https://api.example.com/nexus/webhooks')).toBeVisible()
  await expect(page.getByText('Secret 版本 1')).toBeVisible()

  await page.getByRole('button', { name: '修改 URL' }).click()
  const editingUrl = page.locator('.webhook-section').first().getByRole('textbox')
  await editingUrl.fill('https://new.example.test/webhook')
  await page.getByRole('button', { name: '保存 URL' }).click()
  await expect(page.getByText('https://new.example.test/webhook')).toBeVisible()

  await page.getByRole('button', { name: '暂停投递' }).click()
  await page.getByRole('button', { name: '确认暂停' }).click()
  await expect(page.getByText('投递已暂停；待投递事件不会取消，恢复后将继续处理。')).toBeVisible()
  await page.getByRole('button', { name: '恢复投递' }).click()
  await page.getByRole('button', { name: '确认恢复' }).click()
  await expect(page.getByText('已启用', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '轮换 Secret' }).click()
  await page.getByRole('button', { name: '确认轮换' }).click()
  const dialog = page.getByRole('dialog', { name: '保存新的 Webhook Secret' })
  await expect(dialog.locator('#webhook-secret')).toHaveValue(oneTimeRotatedWebhookSecret)
  await expect(page.getByText('Secret 版本 2')).toBeVisible()
  await dialog.getByText('我已经安全保存这个 Secret').click()
  await dialog.getByRole('button', { name: '完成' }).click()

  expect(api.calls.filter((call) => call.method === 'PUT' && call.path.endsWith('/webhook')).map((call) => call.body)).toEqual([
    { url: 'https://new.example.test/webhook' },
    { status: 1 },
    { status: 0 },
  ])
  expect(api.calls.filter((call) => call.path.endsWith('/rotate-secret'))).toHaveLength(1)
})

test('配置加载失败可以原位重试', async ({ page }) => {
  const api = await installMockApi(page, { webhooks: [sampleWebhook] })
  api.failures.set('GET /api/application/1/webhook', { status: 503, code: 'TEMPORARY_ERROR', message: '暂时失败' })
  await openWebhookTab(page)
  await expect(page.getByRole('heading', { name: '暂时无法加载 Webhook' })).toBeVisible()
  api.failures.delete('GET /api/application/1/webhook')
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByRole('heading', { name: 'Webhook 配置' })).toBeVisible()
})

test('手机端 Webhook 配置没有横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installMockApi(page, { webhooks: [sampleWebhook] })
  await openWebhookTab(page)
  await expect(page.getByText('https://api.example.com/nexus/webhooks')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  await page.getByRole('button', { name: '轮换 Secret' }).click()
  await page.getByRole('button', { name: '确认轮换' }).click()
  await expect(page.locator('#webhook-secret')).toHaveValue(oneTimeRotatedWebhookSecret)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  const dialog = page.getByRole('dialog', { name: '保存新的 Webhook Secret' })
  await dialog.getByText('我已经安全保存这个 Secret').click()
  await dialog.getByRole('button', { name: '完成' }).click()
})
