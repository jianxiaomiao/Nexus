import { expect, test } from '@playwright/test'
import { installMockApi } from './support/mockApi'

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }, { width: 320, height: 568 }]) {
  test(`planet loads without blocking form at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await installMockApi(page)
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto('/login')
    await page.locator('input[type=email]').fill('preview@example.test')
    await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
    await expect(page.locator('.login-planet canvas')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const submit = page.locator('.login-button')
    await submit.scrollIntoViewIfNeeded()
    await expect(submit).toBeVisible()
    const canvas = page.locator('.login-planet canvas')
    await canvas.focus()
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('Home')
    expect(errors).toEqual([])
  })
}

test('graphics chunk failure preserves login validation and poster', async ({ page }) => {
  const api = await installMockApi(page)
  await page.route('**/components/login-planet/scene.js*', route => route.abort())
  await page.goto('/login')
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'fallback')
  await expect(page.locator('.planet-poster')).toBeVisible()
  await page.locator('.login-button').click()
  expect(api.calls.filter(c => c.path === '/api/auth/login')).toHaveLength(0)
  await expect(page.locator('.el-form-item__error')).toHaveCount(2)
})

test('context loss falls back and navigating back creates one new scene', async ({ page }) => {
  await installMockApi(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/login')
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
  await page.locator('canvas').evaluate((canvas: HTMLCanvasElement) => canvas.getContext('webgl2')?.getExtension('WEBGL_lose_context')?.loseContext())
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'fallback')
  await page.locator('.register-prompt a').click()
  await expect(page).toHaveURL(/\/register$/)
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
  await expect(page.locator('.login-planet canvas')).toHaveCount(1)
  await page.goBack()
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
  await expect(page.locator('.login-planet canvas')).toHaveCount(1)
  expect(errors).toEqual([])
})

test('slow graphics and repeated navigation leave form independent', async ({ page }) => {
  await installMockApi(page)
  await page.route('**/components/login-planet/scene.js*', async route => {
    await new Promise(resolve => setTimeout(resolve, 2000))
    await route.continue().catch(() => {})
  })
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/login')
  await page.locator('input[type=email]').fill('slow@example.test')
  await page.locator('.register-prompt a').click()
  await page.waitForTimeout(2500)
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
  await expect(page.locator('.login-planet canvas')).toHaveCount(1)
  await page.goBack()
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
  expect(errors).toEqual([])
})

test('reduced motion remains interactive and can toggle live', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/login')
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
  await page.locator('canvas').focus()
  await page.keyboard.press('ArrowLeft')
  await page.keyboard.press('Home')
  await page.emulateMedia({ reducedMotion: 'no-preference' })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready')
})

for (const width of [1440, 390, 320]) {
  test(`registration shares scene while preserving all fields at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 1440 ? 900 : 844 })
    await installMockApi(page)
    await page.goto('/register')
    await expect(page.locator('.login-planet')).toHaveAttribute('data-state', 'ready', { timeout: 20000 })
    await expect(page.locator('input')).toHaveCount(4)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    await page.locator('.submit-button').scrollIntoViewIfNeeded()
    await expect(page.locator('.submit-button')).toBeVisible()
  })
}
