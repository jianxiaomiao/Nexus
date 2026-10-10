import { expect, test, type Page } from '@playwright/test'
import { installMockApi, oneTimeKey, oneTimeRotatedKey, openApplication, sampleApplication, sampleKey, signIn } from './support/mockApi'

async function openKeysTab(page: Page) {
  await openApplication(page)
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await expect(page).toHaveURL(/\/applications\/1\?tab=keys$/)
}

async function openKeyDetail(page: Page) {
  await openKeysTab(page)
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page).toHaveURL(/\/applications\/1\/keys\/11$/)
}

test('列表到详情只显示 Key Preview，不再显示完整凭证', async ({ page }) => {
  await installMockApi(page)
  await openKeyDetail(page)
  await expect(page.getByRole('heading', { name: '开发环境 Key' })).toBeVisible()
  await expect(page.getByText('test-public-id')).toBeVisible()
  await expect(page.getByRole('note')).toContainText('完整 API Key 仅在创建或轮换成功时显示一次')
  await expect(page.locator('body')).not.toContainText(oneTimeKey)
})

test('创建后只显示一次完整 Key，保存确认前不能关闭', async ({ page }) => {
  const api = await installMockApi(page, { keys: [] })
  await openKeysTab(page)
  await expect(page.getByRole('heading', { name: '还没有 API Key' })).toBeVisible()
  await page.getByRole('button', { name: '创建 API Key' }).first().click()
  const createDialog = page.getByRole('dialog', { name: '创建 API Key' })
  await createDialog.getByPlaceholder('例如：生产环境 Key').fill('  新 Key  ')
  await createDialog.getByRole('button', { name: '创建 Key' }).click()

  const revealDialog = page.getByRole('dialog', { name: 'API Key 创建成功' })
  await expect(revealDialog.getByRole('textbox', { name: '完整 API Key' })).toHaveValue(oneTimeKey)
  await expect(revealDialog.getByRole('button', { name: '完成' })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(revealDialog).toBeVisible()
  await page.evaluate(() => document.querySelector<HTMLAnchorElement>('.back-link')?.click())
  await expect(page).toHaveURL(/\/applications\/1\?tab=keys$/)
  await expect(page.getByText('请先保存完整 API Key，并勾选确认后再离开')).toBeVisible()
  expect(api.calls.find((call) => call.path === '/api/apiKey/create')?.body).toEqual({ applicationId: 1, name: '新 Key' })

  await revealDialog.getByText('我已经保存这枚 API Key').click()
  await revealDialog.getByRole('button', { name: '完成' }).click()
  await expect(revealDialog).not.toBeVisible()
  await expect(page.locator('body')).not.toContainText(oneTimeKey)
  await expect(page.getByText('新 Key', { exact: true }).first()).toBeVisible()
})

test('Key 创建被后端拒绝时保留输入并显示业务错误', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('POST /api/apiKey/create', { status: 409, code: 'API_KEY_NAME_ALREADY_EXISTS', message: '同名' })
  await openKeysTab(page)
  await page.getByRole('button', { name: '创建 API Key' }).click()
  const dialog = page.getByRole('dialog', { name: '创建 API Key' })
  await dialog.getByPlaceholder('例如：生产环境 Key').fill('开发环境 Key')
  await dialog.getByRole('button', { name: '创建 Key' }).click()
  await expect(page.getByText('这个 Key 名称已被使用，请换一个名称')).toBeVisible()
  await expect(dialog.getByPlaceholder('例如：生产环境 Key')).toHaveValue('开发环境 Key')
})

test('已禁用的 Application 不能创建新 Key', async ({ page }) => {
  await installMockApi(page, { applications: [{ ...sampleApplication, status: 1 }] })
  await openKeysTab(page)
  await expect(page.getByText('应用已禁用，现有 Key 无法调用开放 API，也不能创建新 Key。')).toBeVisible()
  await expect(page.getByRole('button', { name: '创建 API Key' })).toBeDisabled()
})

test('Key 列表加载失败可重试', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('GET /api/apiKey/1', { status: 503, code: 'TEMPORARY_ERROR', message: '暂时失败' })
  await openKeysTab(page)
  await expect(page.locator('.notice.error-notice')).toContainText('暂时无法加载 API Key')
  api.failures.delete('GET /api/apiKey/1')
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByText('开发环境 Key', { exact: true }).first()).toBeVisible()
})

test('Key 详情支持改名、禁用与重新启用', async ({ page }) => {
  const api = await installMockApi(page)
  await openKeyDetail(page)
  await page.getByRole('button', { name: '编辑 Key 名称' }).click()
  await page.getByPlaceholder('Key 名称').fill('改名后的 Key')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByRole('heading', { name: '改名后的 Key' })).toBeVisible()

  await page.getByRole('button', { name: '禁用 Key' }).click()
  await page.getByRole('button', { name: '确认禁用' }).click()
  await expect(page.getByRole('button', { name: '启用 Key' })).toBeVisible()
  await page.getByRole('button', { name: '启用 Key' }).click()
  await page.getByRole('button', { name: '确认启用' }).click()
  await expect(page.getByRole('button', { name: '禁用 Key' })).toBeVisible()
  expect(api.calls.filter((call) => call.method === 'PUT' && call.path === '/api/apiKey').map((call) => call.body)).toEqual([
    { applicationId: 1, apiKeyId: 11, name: '改名后的 Key' },
    { applicationId: 1, apiKeyId: 11, status: 1 },
    { applicationId: 1, apiKeyId: 11, status: 0 },
  ])
})

test('轮换须确认，成功后只展示一次新凭据并保留 Key 状态与资源', async ({ page }) => {
  const api = await installMockApi(page)
  await openKeyDetail(page)

  await page.getByRole('button', { name: '轮换凭据' }).click()
  const confirmDialog = page.getByRole('dialog', { name: '轮换 API Key 凭据' })
  await expect(confirmDialog).toContainText('旧凭据立即失效')
  await confirmDialog.getByRole('button', { name: '取消' }).click()
  expect(api.calls.filter((call) => call.path.endsWith('/rotate'))).toHaveLength(0)

  await page.getByRole('button', { name: '轮换凭据' }).click()
  await confirmDialog.getByRole('button', { name: '确认轮换' }).click()
  const revealDialog = page.getByRole('dialog', { name: '轮换成功' })
  const credential = revealDialog.getByRole('textbox', { name: '新的 API Key（仅显示一次）' })
  await expect(credential).toHaveAttribute('type', 'password')
  await expect(credential).toHaveValue(oneTimeRotatedKey)
  await revealDialog.getByRole('button', { name: '显示' }).click()
  await expect(credential).toHaveAttribute('type', 'text')
  await expect(revealDialog.getByRole('button', { name: '我已保存，关闭' })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(revealDialog).toBeVisible()
  await page.evaluate(() => document.querySelector<HTMLAnchorElement>('.back-link')?.click())
  await expect(page).toHaveURL(/\/applications\/1\/keys\/11$/)
  await expect(page.getByText('请先保存完整 API Key，并勾选确认后再离开')).toBeVisible()

  expect(api.calls.filter((call) => call.path.endsWith('/rotate')).map((call) => ({ method: call.method, body: call.body })))
    .toEqual([{ method: 'POST', body: { expectedPublicId: 'test-public-id' } }])
  expect(api.keys[0]?.id).toBe(11)
  expect(api.keys[0]?.status).toBe(0)
  await revealDialog.getByText('我已安全保存新的 API Key').click()
  await revealDialog.getByRole('button', { name: '我已保存，关闭' }).click()
  await expect(revealDialog).not.toBeVisible()
  await expect(page.getByText('rotated-test-public-id')).toBeVisible()
  await expect(page.locator('body')).not.toContainText(oneTimeRotatedKey)
})

test('已禁用 Key 可以轮换，但不会因此启用', async ({ page }) => {
  const api = await installMockApi(page, { keys: [{ ...sampleKey, status: 1 }] })
  await openKeyDetail(page)
  await page.getByRole('button', { name: '轮换凭据' }).click()
  const confirmDialog = page.getByRole('dialog', { name: '轮换 API Key 凭据' })
  await expect(confirmDialog).toContainText('轮换不会自动启用')
  await confirmDialog.getByRole('button', { name: '确认轮换' }).click()
  await expect(page.getByRole('dialog', { name: '轮换成功' })).toBeVisible()
  expect(api.keys[0]?.status).toBe(1)
  await expect(page.getByRole('button', { name: '启用 Key' })).toBeVisible()
})

test('轮换时 Public ID 已变化会提示刷新，不自动重试', async ({ page }) => {
  const api = await installMockApi(page)
  await openKeyDetail(page)
  await expect(page.getByText('test-public-id')).toBeVisible()
  api.keys[0]!.publicId = 'already-rotated-id'
  await page.getByRole('button', { name: '轮换凭据' }).click()
  await page.getByRole('dialog', { name: '轮换 API Key 凭据' }).getByRole('button', { name: '确认轮换' }).click()
  await expect(page.getByText('Public ID 已变化。请刷新详情核对，勿直接重试轮换')).toBeVisible()
  await expect(page.getByRole('dialog', { name: '轮换成功' })).not.toBeVisible()
  expect(api.calls.filter((call) => call.path.endsWith('/rotate'))).toHaveLength(1)
})

test('轮换响应异常时提示结果不确定，不自动重试', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('POST /api/apiKey/11/rotate', { status: 503, code: 'TEMPORARY_ERROR', message: '服务暂时不可用' })
  await openKeyDetail(page)
  await page.getByRole('button', { name: '轮换凭据' }).click()
  await page.getByRole('dialog', { name: '轮换 API Key 凭据' }).getByRole('button', { name: '确认轮换' }).click()
  await expect(page.getByText(/轮换结果可能已生效。请先刷新详情核对 Public ID/)).toBeVisible()
  expect(api.calls.filter((call) => call.path.endsWith('/rotate'))).toHaveLength(1)
})

test('删除 Key 需要输入正确名称，并回到所属应用 Key 列表', async ({ page }) => {
  const api = await installMockApi(page)
  await openKeyDetail(page)
  await page.getByRole('button', { name: '删除 Key' }).click()
  await page.getByPlaceholder('输入 Key 名称').fill('错的名称')
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('名称不一致，请重新输入')).toBeVisible()
  expect(api.calls.filter((call) => call.method === 'DELETE')).toHaveLength(0)
  await page.getByPlaceholder('输入 Key 名称').fill('开发环境 Key')
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page).toHaveURL(/\/applications\/1\?tab=keys$/)
  await expect(page.getByRole('heading', { name: '还没有 API Key' })).toBeVisible()
  expect(api.calls.find((call) => call.method === 'DELETE')?.body).toEqual({ applicationId: 1, apiKeyId: 11 })
})

test('Key 不在所属应用列表时不显示详情', async ({ page }) => {
  const api = await installMockApi(page)
  await signIn(page)
  await page.getByRole('article').filter({ hasText: '示例应用' }).getByRole('link', { name: '查看详情' }).click()
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  api.keys.splice(0)
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page.locator('.detail-card.error-card')).toContainText('这个 API Key 不存在，或你没有访问权限')
})
