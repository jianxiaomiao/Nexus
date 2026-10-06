import { expect, test } from '@playwright/test'
import { installMockApi, openApplication, signIn } from './support/mockApi'

test('空列表可创建应用，名称去空格后发送', async ({ page }) => {
  const api = await installMockApi(page, { applications: [] })
  await signIn(page)
  await expect(page.getByRole('heading', { name: '从第一个 Application 开始' })).toBeVisible()
  await page.getByRole('button', { name: '创建 Application' }).first().click()
  const dialog = page.getByRole('dialog', { name: '创建 Application' })
  await dialog.getByRole('button', { name: '创建应用' }).click()
  await expect(dialog.getByText('请输入应用名称')).toBeVisible()
  expect(api.calls.filter((call) => call.path === '/api/application/create')).toHaveLength(0)
  await dialog.getByPlaceholder('例如：我的第一个应用').fill('  新应用  ')
  await dialog.getByRole('button', { name: '创建应用' }).click()
  await expect(page.getByRole('article').filter({ hasText: '新应用' })).toBeVisible()
  expect(api.calls.find((call) => call.path === '/api/application/create')?.body).toEqual({ name: '新应用' })
})

test('应用列表加载失败可重试', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('GET /api/application', { status: 503, code: 'TEMPORARY_ERROR', message: '暂时失败' })
  await signIn(page)
  await expect(page.locator('.notice.error-notice')).toContainText('暂时无法加载应用')
  api.failures.delete('GET /api/application')
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByRole('article').filter({ hasText: '示例应用' })).toBeVisible()
})

test('创建时重复名称显示业务错误并保留输入', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('POST /api/application/create', { status: 409, code: 'APPLICATION_NAME_ALREADY_EXISTS', message: '名称已存在' })
  await signIn(page)
  await page.getByRole('button', { name: '创建 Application' }).click()
  const dialog = page.getByRole('dialog', { name: '创建 Application' })
  await dialog.getByPlaceholder('例如：我的第一个应用').fill('示例应用')
  await dialog.getByRole('button', { name: '创建应用' }).click()
  await expect(page.getByText('这个应用名称已被使用，请换一个名称')).toBeVisible()
  await expect(dialog.getByPlaceholder('例如：我的第一个应用')).toHaveValue('示例应用')
})

test('卡片更多菜单可以编辑应用名称', async ({ page }) => {
  const api = await installMockApi(page)
  await signIn(page)
  await page.getByRole('button', { name: '更多操作：示例应用' }).click()
  await page.getByText('编辑名称', { exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑 Application' })
  await dialog.getByPlaceholder('例如：我的第一个应用').fill('卡片改名')
  await dialog.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByRole('article').filter({ hasText: '卡片改名' })).toBeVisible()
  expect(api.calls.find((call) => call.method === 'PUT' && call.path === '/api/application')?.body).toEqual({ id: 1, name: '卡片改名' })
})

test('详情页显示应用，支持改名、禁用和重新启用', async ({ page }) => {
  const api = await installMockApi(page)
  await openApplication(page)
  await expect(page.getByRole('heading', { name: '应用信息' })).toBeVisible()
  await page.getByRole('button', { name: '编辑应用名称' }).click()
  await page.getByPlaceholder('应用名称').fill('详情改名')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByRole('heading', { name: '详情改名' })).toBeVisible()

  await page.getByRole('button', { name: '禁用应用' }).click()
  await page.getByRole('button', { name: '确认禁用' }).click()
  await expect(page.getByRole('button', { name: '启用应用' })).toBeVisible()
  await page.getByRole('button', { name: '启用应用' }).click()
  await page.getByRole('button', { name: '确认启用' }).click()
  await expect(page.getByRole('button', { name: '禁用应用' })).toBeVisible()
  expect(api.calls.filter((call) => call.method === 'PUT' && call.path === '/api/application').map((call) => call.body)).toEqual([
    { id: 1, name: '详情改名' }, { id: 1, status: 1 }, { id: 1, status: 0 },
  ])
})

test('删除应用必须输入正确名称，成功后返回列表', async ({ page }) => {
  const api = await installMockApi(page)
  await openApplication(page)
  await page.getByRole('button', { name: '删除应用' }).click()
  await page.getByPlaceholder('输入应用名称').fill('错的名称')
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('名称不一致，请重新输入')).toBeVisible()
  expect(api.calls.filter((call) => call.method === 'DELETE')).toHaveLength(0)
  await page.getByPlaceholder('输入应用名称').fill('示例应用')
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('heading', { name: '从第一个 Application 开始' })).toBeVisible()
  expect(api.calls.filter((call) => call.method === 'DELETE').map((call) => call.path)).toEqual(['/api/application/1'])
})

test('应用不在本人列表时详情页不显示资源', async ({ page }) => {
  const api = await installMockApi(page)
  await signIn(page)
  api.applications.splice(0)
  await page.getByRole('article').filter({ hasText: '示例应用' }).getByRole('link', { name: '查看详情' }).click()
  await expect(page.locator('.detail-card.error-card')).toContainText('这个应用不存在，或你没有访问权限')
})
