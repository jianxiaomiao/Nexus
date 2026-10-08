import { expect, test } from '@playwright/test'
import { installMockApi, sampleApplication, signIn } from './support/mockApi'

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

for (const viewport of [{ width: 1280, height: 600 }, { width: 390, height: 600 }]) {
  test(`${viewport.width}px 控制台只滚动内容区域，切换页面后从顶部开始`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await installMockApi(page, { applications: Array.from({ length: 10 }, (_, index) => ({
      ...sampleApplication, id: index + 1, name: `滚动测试应用 ${index + 1}`,
    })) })
    await signIn(page)

    const scrollArea = page.locator('.console-scroll-area > .el-scrollbar__wrap')
    await expect(page.locator('.console-scroll-area > .el-scrollbar__bar.is-vertical')).toHaveCount(1)
    await expect.poll(() => scrollArea.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true)
    const sidebarTop = await page.locator('.console-sidebar').evaluate((element) => element.getBoundingClientRect().top)
    const headerTop = await page.locator('.console-header').evaluate((element) => element.getBoundingClientRect().top)
    await scrollArea.hover()
    await page.mouse.wheel(0, 1000)
    await expect.poll(() => scrollArea.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
    expect(await page.evaluate(() => window.scrollY)).toBe(0)
    expect(await page.evaluate(() => document.documentElement.scrollHeight - innerHeight)).toBeLessThanOrEqual(1)
    expect(await page.locator('.console-sidebar').evaluate((element) => element.getBoundingClientRect().top)).toBe(sidebarTop)
    expect(await page.locator('.console-header').evaluate((element) => element.getBoundingClientRect().top)).toBe(headerTop)

    if (viewport.width < 700) await page.getByRole('button', { name: '打开导航' }).click()
    await page.getByRole('link', { name: 'API 调试台' }).click()
    await expect(page).toHaveURL(/\/playground$/)
    await expect.poll(() => scrollArea.evaluate((element) => element.scrollTop)).toBe(0)
  })
}
