import { expect, test } from '@playwright/test'
import { installMockApi, signIn } from './support/mockApi'

test.use({ viewport: { width: 390, height: 844 } })

test('手机端导航默认收起，可以打开并用 Escape 关闭', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  const nav = page.getByRole('navigation', { name: '主导航' })
  await expect(nav).toBeHidden()
  await page.getByRole('button', { name: '打开导航' }).click()
  await expect(nav).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(nav).toBeHidden()
  await expect(page.getByRole('button', { name: '打开导航' })).toHaveAttribute('aria-expanded', 'false')
})

test('手机端 Key 列表切换为卡片，仍可进入详情', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('article').filter({ hasText: '示例应用' }).getByRole('link', { name: '查看详情' }).click()
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  const mobileKey = page.locator('.mobile-key').filter({ hasText: '开发环境 Key' })
  await expect(mobileKey).toBeVisible()
  await mobileKey.getByRole('link', { name: '查看详情' }).click()
  await expect(page.getByRole('heading', { name: '开发环境 Key' })).toBeVisible()
})
