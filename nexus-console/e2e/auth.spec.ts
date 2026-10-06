import { expect, test, type Page } from '@playwright/test'
import { installMockApi, signIn } from './support/mockApi'

async function submitLogin(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入邮箱').fill('learner@example.test')
  await page.getByPlaceholder('请输入密码').fill('example-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

async function fillRegistration(page: Page) {
  await page.goto('/register')
  await page.getByPlaceholder('请输入常用邮箱').fill('learner@example.test')
  await page.getByPlaceholder('给自己取个名字').fill('学习者')
  await page.getByPlaceholder('设置密码').fill('example-password')
  await page.getByPlaceholder('请再次输入密码').fill('example-password')
}

test('登录后进入 Application 列表，并在请求中携带 Bearer', async ({ page }) => {
  const api = await installMockApi(page)
  await signIn(page)
  await expect(page.getByRole('article').filter({ hasText: '示例应用' })).toBeVisible()
  expect(api.calls.find((call) => call.path === '/api/auth/login')?.body).toEqual({
    email: 'learner@example.test', password: 'example-password',
  })
})

test('未登录访问管理页面会转到登录页', async ({ page }) => {
  await page.goto('/applications/1')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { name: '登录 Nexus' })).toBeVisible()
})

test('登录表单校验失败时不调用后端', async ({ page }) => {
  const api = await installMockApi(page)
  await page.goto('/login')
  await page.getByPlaceholder('请输入邮箱').fill('not-an-email')
  await page.getByPlaceholder('请输入密码').fill('example-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('邮箱格式不正确')).toBeVisible()
  expect(api.calls.filter((call) => call.path === '/api/auth/login')).toHaveLength(0)
})

test('错误凭证显示统一提示，不进入管理页面', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('POST /api/auth/login', { status: 401, code: 'AUTH_INVALID_CREDENTIALS', message: '认证失败' })
  await submitLogin(page)
  await expect(page.getByText('邮箱或密码错误')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)
})

test('被封禁账号显示封禁提示', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('POST /api/auth/login', { status: 403, code: 'AUTH_ACCOUNT_FORBIDDEN', message: '禁止登录' })
  await submitLogin(page)
  await expect(page.getByText('账号已封禁')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)
})

test('退出登录后无法再访问管理页面', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/applications')
  await expect(page).toHaveURL(/\/login$/)
})

test('刷新页面后内存会话消失，需要重新登录', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.reload()
  await expect(page).toHaveURL(/\/login$/)
})

test('管理接口确认 JWT 无效后清除会话', async ({ page }) => {
  const api = await installMockApi(page)
  await signIn(page)
  api.failures.set('GET /api/application', { status: 401, code: 'INVALID_ACCESS_TOKEN', message: '凭证无效' })
  await page.getByRole('article').filter({ hasText: '示例应用' }).getByRole('link', { name: '查看详情' }).click()
  await expect(page.locator('.detail-card.error-card')).toContainText('登录已过期，请重新登录')
  await page.getByRole('link', { name: '返回 Applications' }).click()
  await expect(page).toHaveURL(/\/login$/)
})

test('其他 401 业务错误不误清会话', async ({ page }) => {
  const api = await installMockApi(page)
  await signIn(page)
  api.failures.set('GET /api/application', { status: 401, code: 'OTHER_UNAUTHORIZED', message: '其他错误' })
  await page.getByRole('article').filter({ hasText: '示例应用' }).getByRole('link', { name: '查看详情' }).click()
  await expect(page.locator('.detail-card.error-card')).toContainText('加载应用失败，请稍后重试')
  api.failures.delete('GET /api/application')
  await page.getByRole('link', { name: '返回 Applications' }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('article').filter({ hasText: '示例应用' })).toBeVisible()
})

test('注册时两次密码不一致，不发送请求', async ({ page }) => {
  const api = await installMockApi(page)
  await fillRegistration(page)
  await page.getByPlaceholder('请再次输入密码').fill('different-password')
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByText('两次输入的密码不一致')).toBeVisible()
  expect(api.calls.filter((call) => call.path === '/api/auth/register')).toHaveLength(0)
})

test('注册成功只发送后端 DTO 字段，并引导登录', async ({ page }) => {
  const api = await installMockApi(page)
  await fillRegistration(page)
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('注册成功，请登录')).toBeVisible()
  expect(api.calls.find((call) => call.path === '/api/auth/register')?.body).toEqual({
    email: 'learner@example.test', displayName: '学习者', password: 'example-password',
  })
})

test('重复邮箱留在注册页并给出明确提示', async ({ page }) => {
  const api = await installMockApi(page)
  api.failures.set('POST /api/auth/register', { status: 409, code: 'AUTH_EMAIL_ALREADY_REGISTERED', message: '邮箱已存在' })
  await fillRegistration(page)
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByText('该邮箱已被注册，请直接登录')).toBeVisible()
  await expect(page).toHaveURL(/\/register$/)
})
