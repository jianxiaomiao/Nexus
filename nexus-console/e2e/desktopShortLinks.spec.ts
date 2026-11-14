import { expect, test } from '@playwright/test'
import type { NexusDesktop } from '../src/types/desktop'
import { installMockApi, signIn } from './support/mockApi'

type TestWindow = Window & { __shortLinkUi: { calls: string[]; reject: boolean; copyFails: boolean } }

for (const missing of [false, true]) {
  test(missing ? '旧桌面桥缺少短链能力时提示且不回退网页导航' : '桌面IPC拒绝和复制失败可提示并重试', async ({ page }) => {
    await page.addInitScript(missing => {
      const control = { calls: [] as string[], reject: true, copyFails: true }
      const unused = async (): Promise<never> => { throw Error('Unexpected screenshot operation') }
      ;(window as unknown as TestWindow).__shortLinkUi = control
      window.nexusDesktop = {
        saveScreenshot: unused,
        startScreenshotTracking: unused,
        stopScreenshotTracking: unused,
        getScreenshotResult: unused,
        readScreenshotTracking: async () => ({ status: 'ok', snapshot: { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null } }),
        onScreenshotTrackingUpdated: () => () => {},
        ...(missing ? {} : { openShortLink: async (code: string) => {
          control.calls.push(code)
          if (control.reject) throw Error('Controlled IPC rejection')
          return { status: 'opened' as const }
        } }),
      } satisfies NexusDesktop
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => {
        if (control.copyFails) throw Error('Controlled clipboard failure')
      } } })
    }, missing)
    const mock = await installMockApi(page)
    mock.shortLinks.push({ id: 123, apiKeyId: 11, name: '桌面短链', shortCode: 'Ab12Cd34Ef56', originalUrl: 'https://weibo.com/1', status: 0, expiresAt: '2030-01-01T00:00:00Z', createdAt: '2026-11-14T00:00:00Z', updatedAt: '2026-11-14T00:00:00Z' })
    await signIn(page)
    await page.getByRole('link', { name: '短链接', exact: true }).click()
    const table = page.locator('.short-links-table')
    const open = table.getByRole('button', { name: '打开 桌面短链 的短地址', exact: true })
    await open.click()
    await expect(page.getByText(missing ? '当前桌面版本不支持打开短链，请复制短地址后在浏览器中访问' : '桌面通信失败，请稍后重试', { exact: true })).toBeVisible()
    await expect(page).toHaveURL(/\/short-links/)
    await expect(open).toBeEnabled()
    if (missing) {
      expect(await page.evaluate(() => (window as unknown as TestWindow).__shortLinkUi.calls)).toEqual([])
      return
    }
    await page.evaluate(() => { (window as unknown as TestWindow).__shortLinkUi.reject = false })
    await open.click()
    await expect(open).toBeEnabled()
    expect(await page.evaluate(() => (window as unknown as TestWindow).__shortLinkUi.calls)).toEqual(['Ab12Cd34Ef56', 'Ab12Cd34Ef56'])
    const copy = table.getByRole('button', { name: '复制 桌面短链 的短地址', exact: true })
    await copy.click()
    await expect(page.getByText('复制失败，请手动选择短地址', { exact: true })).toBeVisible()
    await page.evaluate(() => { (window as unknown as TestWindow).__shortLinkUi.copyFails = false })
    await copy.click()
    await expect(page.getByText('短地址已复制', { exact: true })).toBeVisible()
  })
}
