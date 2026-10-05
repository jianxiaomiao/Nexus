import { expect, test } from '@playwright/test'

test('登录后可以看到自己的 Application', async ({ page }) => {
  // 所有 /api 请求都在浏览器中拦截；未列出的接口直接失败，不会碰本地 MySQL。
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname

    if (path === '/api/auth/login' && request.method() === 'POST') {
      await route.fulfill({
        status: 200,
        json: {
          code: 'SUCCESS',
          message: '登录成功',
          data: {
            accessToken: 'playwright-only-token',
            tokenType: 'Bearer',
            expiresInSeconds: 1800,
          },
        },
      })
      return
    }

    if (path === '/api/application' && request.method() === 'GET') {
      expect(request.headers().authorization).toBe('Bearer playwright-only-token')
      await route.fulfill({
        status: 200,
        json: {
          code: 'SUCCESS',
          message: '查询成功',
          data: [{
            id: 1,
            name: 'Playwright 示例应用',
            status: 0,
            createdAt: '2026-10-05T10:00:00',
            updatedAt: '2026-10-05T10:00:00',
          }],
        },
      })
      return
    }

    await route.abort()
  })

  await page.goto('/login')
  await page.getByPlaceholder('请输入邮箱').fill('learner@example.test')
  await page.getByPlaceholder('请输入密码').fill('example-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('heading', { name: 'Applications', exact: true })).toBeVisible()
  await expect(page.getByRole('article').filter({ hasText: 'Playwright 示例应用' })).toBeVisible()
})
