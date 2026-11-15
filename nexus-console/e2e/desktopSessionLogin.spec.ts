import { expect, test } from '@playwright/test'
import type { WriteAuthSessionInput, WriteAuthSessionResult } from '../src/types/desktop'
import { installMockApi } from './support/mockApi'

type Control = { input: WriteAuthSessionInput | null; release: (ok: boolean) => void }
type TestWindow = Window & { __sessionLogin: Control }

for (const succeeds of [true, false]) {
  test(succeeds ? '桌面登录等待Main保存后才跳转' : 'Main保存失败不建立本地登录、不跳转', async ({ page }) => {
    await page.addInitScript(() => {
      const unused = async (): Promise<never> => { throw Error('Unexpected desktop operation') }
      const control: Control = { input: null, release: () => { throw Error('No pending write') } }
      ;(window as unknown as TestWindow).__sessionLogin = control
      window.nexusDesktop = {
        readAuthSession: async () => ({ status: 'ok', authState: { revision: 8, session: null } }),
        writeAuthSession: input => new Promise<WriteAuthSessionResult>(resolve => {
          control.input = input
          control.release = ok => resolve(ok
            ? { status: 'ok', code: 'SUCCESS', authState: { revision: 9, session: input.session } }
            : { status: 'failed', code: 'FORBIDDEN' })
        }),
        clearAuthSession: unused,
        saveScreenshot: unused,
        startScreenshotTracking: unused,
        stopScreenshotTracking: unused,
        getScreenshotResult: unused,
        readScreenshotTracking: async () => ({ status: 'ok', snapshot: { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null } }),
        onScreenshotTrackingUpdated: () => () => {},
      }
    })
    await installMockApi(page)
    await page.goto('/login')
    await page.getByPlaceholder('请输入邮箱').fill('learner@example.test')
    await page.getByPlaceholder('请输入密码').fill('example-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__sessionLogin.input !== null)).toBe(true)
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByText('登录成功', { exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => (window as unknown as TestWindow).__sessionLogin.input?.expectedRevision)).toBe(8)
    await page.evaluate(ok => (window as unknown as TestWindow).__sessionLogin.release(ok), succeeds)
    if (succeeds) {
      await expect(page).toHaveURL(/\/applications$/)
      await expect(page.getByRole('article').filter({ hasText: '示例应用' })).toBeVisible()
    } else {
      await expect(page.getByText('无法保存登录会话，请刷新后重新登录')).toBeVisible()
      await expect(page).toHaveURL(/\/login$/)
      await expect(page.getByText('登录成功', { exact: true })).toHaveCount(0)
    }
  })
}
