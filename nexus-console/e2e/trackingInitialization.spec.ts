import { expect, test, type Page } from '@playwright/test'
import type { Trace } from '../src/api/Trace'
import type { ReadScreenshotTrackingResult, ScreenshotTrackingListener } from '../src/types/desktop'

type Control = {
  subscriptions: number
  removals: number
  stopCalls: number
  reads: ((result: ReadScreenshotTrackingResult) => void)[]
  listener: ScreenshotTrackingListener | null
  lastListener: ScreenshotTrackingListener | null
}
type TestWindow = Window & { __trackingInitTest: Control }
const idle: Trace = { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null }
function trace(status: 'RUNNING' | 'SUCCEEDED'): Trace {
  return {
    jobId: '11111111-2222-4333-8444-555555555555',
    traceStatus: status === 'RUNNING' ? 'POLLING' : 'TERMINAL',
    queryFailedInfo: null,
    latestJob: {
      jobId: '11111111-2222-4333-8444-555555555555', status, attemptCount: 1,
      createdAt: '2026-11-12T00:00:00Z', updatedAt: '2026-11-12T00:00:01Z',
      resultSizeBytes: null, resultSha256: null, blockedResources: null,
      resourceLimitReached: null, resultExpiresAt: null, failureCode: null,
      startedAt: null, completedAt: null,
    },
  }
}
async function installBridge(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as TestWindow
    const control: Control = w.__trackingInitTest = { subscriptions: 0, removals: 0, stopCalls: 0, reads: [], listener: null, lastListener: null }
    w.nexusDesktop = {
      saveScreenshot: async () => ({ status: 'cancelled' }),
      getScreenshotResult: async () => ({ status: 'failed', code: 'NOT_READY' }),
      startScreenshotTracking: async () => ({ status: 'failed', code: 'INVALID_REQUEST' }),
      readScreenshotTracking: () => new Promise(resolve => { control.reads.push(resolve) }),
      stopScreenshotTracking: async () => { control.stopCalls++; return { status: 'failed', code: 'FORBIDDEN' } },
      onScreenshotTrackingUpdated: listener => {
        control.subscriptions++
        control.listener = listener
        control.lastListener = listener
        return () => { control.removals++; control.listener = null }
      },
    }
  })
  await page.goto('/login')
  await expect(page.locator('input[type=password]')).toBeVisible()
  await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__trackingInitTest.reads.length)).toBe(1)
}
async function snapshot(page: Page): Promise<Trace> {
  return page.evaluate(async () => {
    // 同一个 Vite 模块地址，读取真实 App 使用的真实 Pinia Store。
    const modulePath = '/src/stores/screenshotTrackingStore.ts'
    const { useScreenshotTrackingStore } = await import(modulePath)
    return JSON.parse(JSON.stringify(useScreenshotTrackingStore().snapshot))
  })
}
async function resolveRead(page: Page, value: Trace) {
  await page.evaluate(value => { (window as unknown as TestWindow).__trackingInitTest.reads[0]!({ status: 'ok', snapshot: value }) }, value)
}
async function push(page: Page, value: Trace) {
  await page.evaluate(value => { (window as unknown as TestWindow).__trackingInitTest.listener!(value) }, value)
}

test('根部首次读取初始化真实Store，页面切换不重复订阅', async ({ page }) => {
  await installBridge(page)
  expect(await snapshot(page)).toEqual(idle)
  await resolveRead(page, trace('RUNNING'))
  await expect.poll(() => snapshot(page)).toEqual(trace('RUNNING'))
  await page.getByRole('link', { name: '创建账号' }).click()
  await expect(page).toHaveURL(/\/register$/)
  expect(await page.evaluate(() => (window as unknown as TestWindow).__trackingInitTest.subscriptions)).toBe(1)
  expect(await snapshot(page)).toEqual(trace('RUNNING'))
})

test('首次读取后收到新推送，Store更新至终态', async ({ page }) => {
  await installBridge(page)
  await resolveRead(page, trace('RUNNING'))
  await expect.poll(() => snapshot(page)).toEqual(trace('RUNNING'))
  await push(page, trace('SUCCEEDED'))
  await expect.poll(() => snapshot(page)).toEqual(trace('SUCCEEDED'))
})

test('新推送先到时，迟到的首次读取不能覆盖新快照', async ({ page }) => {
  await installBridge(page)
  await push(page, trace('SUCCEEDED'))
  await expect.poll(() => snapshot(page)).toEqual(trace('SUCCEEDED'))
  await resolveRead(page, trace('RUNNING'))
  expect(await snapshot(page)).toEqual(trace('SUCCEEDED'))
})

test('真实App卸载移除订阅，迟到读取和已排队回调不更新Store', async ({ page }) => {
  await installBridge(page)
  expect(await snapshot(page)).toEqual(idle)
  await page.evaluate(() => {
    const root = document.querySelector('#app') as HTMLElement & { __vue_app__: { unmount(): void } }
    root.__vue_app__.unmount()
  })
  expect(await page.evaluate(() => (window as unknown as TestWindow).__trackingInitTest.removals)).toBe(1)
  expect(await page.evaluate(() => (window as unknown as TestWindow).__trackingInitTest.listener === null)).toBe(true)
  await resolveRead(page, trace('RUNNING'))
  await page.evaluate(value => { (window as unknown as TestWindow).__trackingInitTest.lastListener!(value) }, trace('SUCCEEDED'))
  expect(await snapshot(page)).toEqual(idle)
  expect(await page.evaluate(() => (window as unknown as TestWindow).__trackingInitTest.stopCalls)).toBe(0)
})

test('普通浏览器没有桌面桥时仍正常挂载', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/login')
  await expect(page.locator('input[type=password]')).toBeVisible()
  expect(await page.evaluate(() => window.nexusDesktop)).toBeUndefined()
  expect(await snapshot(page)).toEqual(idle)
  expect(errors).toEqual([])
})
