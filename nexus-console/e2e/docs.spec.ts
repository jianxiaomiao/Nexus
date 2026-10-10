import { expect, test } from '@playwright/test'
import { installMockApi, signIn } from './support/mockApi'

test('文档入口、首页卡片与 Markdown 文章可阅读', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: '开发文档' }).click()
  await expect(page).toHaveURL(/\/docs$/)
  await expect(page.getByRole('heading', { name: '从第一条 API 调用开始' })).toBeVisible()
  await expect(page.locator('.docs-hero-image')).toBeVisible()
  await expect(page.locator('.docs-card')).toHaveCount(7)
  await expect(page.locator('.docs-card-icon svg')).toHaveCount(7)

  await page.locator('.docs-card').filter({ hasText: '计算 Hash' }).click()
  await expect(page).toHaveURL(/\/docs\/hash$/)
  await expect(page.getByRole('heading', { name: '计算 Hash', exact: true })).toBeVisible()
  await expect(page.locator('.docs-markdown table').first()).toBeVisible()
  await expect(page.locator('.docs-markdown pre').first()).toBeVisible()
  await expect(page.locator('.docs-markdown')).toContainText('UTF-8 编码后最多 4096 字节')
  await expect(page.getByRole('complementary', { name: '本页目录' }).getByRole('link', { name: '请求参数' })).toHaveAttribute('href', '#请求参数')
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: '复制代码' }).first().click()
  await expect(page.getByRole('button', { name: '代码已复制' })).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('POST /v1/utils/hash')
  await page.getByRole('link', { name: '返回控制台' }).click()
  await expect(page).toHaveURL(/\/applications$/)
})

test('七篇文档都呈现与当前接口一致的关键内容', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: '开发文档' }).click()
  const directory = page.getByRole('complementary', { name: '文档目录' })
  const cases = [
    { title: '快速开始', expected: '完整 Key 只显示这一次' },
    { title: 'API Key 与认证', expected: '程序调用身份' },
    { title: '生成 UUID', expected: 'GET /v1/utils/uuid' },
    { title: '计算 Hash', expected: '恰好 4096 字节有效' },
    { title: '短链接', expected: 'POST /v1/short-links' },
    { title: '网页正文提取', expected: 'POST /v1/web/extract' },
    { title: '错误码与排查', expected: 'INVALID_API_KEY_CREDENTIAL' },
  ]

  for (const item of cases) {
    await directory.getByRole('link', { name: item.title, exact: true }).click()
    await expect(page.getByRole('heading', { name: item.title, exact: true })).toBeVisible()
    await expect(page.locator('.docs-markdown')).toContainText(item.expected)
  }
})

test('文档路由需要登录，窄屏目录可以打开和关闭', async ({ page }) => {
  await page.goto('/docs/hash')
  await expect(page).toHaveURL(/\/login$/)
  await installMockApi(page)
  await signIn(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.getByRole('link', { name: '开发文档' }).click()
  await page.locator('.docs-card').filter({ hasText: '计算 Hash' }).click()
  await expect(page).toHaveURL(/\/docs\/hash$/)
  await page.getByRole('button', { name: '打开文档目录' }).click()
  await expect(page.getByRole('complementary', { name: '文档目录' })).toHaveClass(/is-open/)
  await page.getByRole('complementary', { name: '文档目录' }).getByRole('link', { name: '生成 UUID' }).click()
  await expect(page).toHaveURL(/\/docs\/uuid$/)
  await expect(page.getByRole('button', { name: '打开文档目录' })).toBeVisible()
})
