import { expect, test, type Page } from '@playwright/test'
import type { Trace } from '../src/api/Trace'
import type { GetScreenshotResult, ScreenshotTrackingListener, StartScreenshotTrackingResult } from '../src/types/desktop'
import { installMockApi, signIn } from './support/mockApi'

const jobId = '11111111-2222-4333-8444-555555555555'
const created = {
  jobId, status: 'QUEUED', attemptCount: 0,
  createdAt: '2026-11-12T00:00:00Z', updatedAt: '2026-11-12T00:00:00Z',
}
type Control = {
  snapshot: Trace
  listener: ScreenshotTrackingListener | null
  mode: 'ok' | 'fail' | 'reject' | 'hold'
  resolveStart: ((result: StartScreenshotTrackingResult) => void) | null
  starts: number
  stops: number
  subscriptions: number
  results: { jobId: string; resolve: (value: GetScreenshotResult) => void; reject: () => void }[]
}
type TestWindow = Window & { __desktopDisplay: Control }

async function setup(page: Page, mode: Control['mode'] = 'ok') {
  await page.addInitScript(mode => {
    const c: Control = {
      snapshot: { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null },
      listener: null, mode, resolveStart: null, starts: 0, stops: 0, subscriptions: 0, results: [],
    }
    ;(window as unknown as TestWindow).__desktopDisplay = c
    window.nexusDesktop = {
      saveScreenshot: async () => ({ status: 'cancelled' }),
      getScreenshotResult: jobId => new Promise((resolve, reject) => {
        c.results.push({ jobId, resolve, reject: () => reject(new Error('Controlled download failure')) })
      }),
      readScreenshotTracking: async () => ({ status: 'ok', snapshot: c.snapshot }),
      stopScreenshotTracking: async () => { c.stops++; return { status: 'ok', snapshot: c.snapshot } },
      onScreenshotTrackingUpdated: listener => {
        c.subscriptions++; c.listener = listener
        return () => { c.listener = null }
      },
      startScreenshotTracking: async input => {
        c.starts++
        if (c.mode === 'fail') return { status: 'failed', code: 'BUSY' }
        if (c.mode === 'reject') throw new Error('Controlled IPC rejection')
        c.snapshot = { jobId: input.jobId, latestJob: null, traceStatus: 'POLLING', queryFailedInfo: null }
        c.listener?.(c.snapshot)
        if (c.mode === 'hold') return new Promise(resolve => { c.resolveStart = resolve })
        return { status: 'ok', snapshot: c.snapshot }
      },
    }
  }, mode)
  await installMockApi(page)
  let posts = 0
  let rendererQueries = 0
  await page.route('**/v1/web/screenshot-jobs', async route => {
    posts++
    await route.fulfill({ status: 202, json: { code: 'SUCCESS', message: 'created', data: created } })
  })
  await page.route('**/v1/web/screenshot-jobs/*', async route => {
    rendererQueries++
    await route.fulfill({ status: 500, json: {} })
  })
  await signIn(page)
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await page.getByLabel('API Key', { exact: true }).fill('PLAYWRIGHT_ONLY_MACHINE_KEY')
  await page.locator('#playground-screenshot-url').fill('https://zhuanlan.zhihu.com/p/123456')
  await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
  return { posts: () => posts, rendererQueries: () => rendererQueries }
}

async function push(page: Page, status: 'RUNNING' | 'SUCCEEDED') {
  await page.evaluate(({ created, status }) => {
    const c = (window as unknown as TestWindow).__desktopDisplay
    c.snapshot = {
      jobId: created.jobId, traceStatus: status === 'RUNNING' ? 'POLLING' : 'TERMINAL', queryFailedInfo: null,
      latestJob: {
        ...created, status, attemptCount: 1, resultSizeBytes: null, resultSha256: null,
        blockedResources: null, resourceLimitReached: null, resultExpiresAt: null,
        failureCode: null, startedAt: null, completedAt: null,
      },
    }
    c.listener?.(c.snapshot)
  }, { created, status })
}

test('首次查询等待、离开页面后推送及返回展示，不启动Renderer轮询', async ({ page }) => {
  const counts = await setup(page)
  const response = page.locator('.playground-response')
  await expect(response).toContainText('已接手，正在查询任务状态')
  await expect(response).toContainText(jobId)
  await expect(response).not.toContainText('填写 API Key 并发送请求后')
  await push(page, 'RUNNING')
  await expect(response).toContainText('生成中')
  await page.getByRole('link', { name: 'Applications', exact: true }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await push(page, 'SUCCEEDED')
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await expect(response).toContainText('截图任务已完成')
  await expect(page.getByLabel('API Key', { exact: true })).toHaveValue('')
  await expect(page.getByRole('button', { name: '创建异步任务', exact: true })).toBeDisabled()
  expect(counts.posts()).toBe(1)
  expect(counts.rendererQueries()).toBe(0)
  expect(await page.evaluate(() => {
    const c = (window as unknown as TestWindow).__desktopDisplay
    return { stops: c.stops, subscriptions: c.subscriptions }
  })).toEqual({ stops: 0, subscriptions: 1 })
})

for (const mode of ['fail', 'reject'] as const) {
  test(`Main接手${mode === 'fail' ? '失败' : '结果不确定'}保留Job ID并禁止再次提交`, async ({ page }) => {
    const counts = await setup(page, mode)
    const response = page.locator('.playground-response')
    await expect(response).toContainText(mode === 'fail' ? '任务已创建，接手未成功' : '任务已创建，接手结果暂未确认')
    await expect(response).toContainText(jobId)
    await expect(page.getByRole('button', { name: '创建异步任务', exact: true })).toBeDisabled()
    expect(counts.posts()).toBe(1)
    expect(counts.rendererQueries()).toBe(0)
  })
}

test('新终态推送早于start返回，页面保持终态且按钮反映提交等待', async ({ page }) => {
  await setup(page, 'hold')
  await expect(page.getByRole('button', { name: '发送中…', exact: true })).toBeDisabled()
  await push(page, 'SUCCEEDED')
  await expect(page.locator('.playground-response')).toContainText('截图任务已完成')
  await page.evaluate(() => {
    const c = (window as unknown as TestWindow).__desktopDisplay
    c.resolveStart!({ status: 'ok', snapshot: { jobId: c.snapshot.jobId, latestJob: null, traceStatus: 'POLLING', queryFailedInfo: null } })
  })
  await expect(page.getByRole('button', { name: '创建异步任务', exact: true })).toBeDisabled()
  await expect(page.locator('.playground-response')).toContainText('截图任务已完成')
  await expect(page.locator('.playground-response')).not.toContainText('尚未确认接手')
})

async function resultCount(page: Page) {
  return page.evaluate(() => (window as unknown as TestWindow).__desktopDisplay.results.length)
}

async function finishPng(page: Page, index: number) {
  await page.evaluate(async index => {
    const c = (window as unknown as TestWindow).__desktopDisplay
    const request = c.results[index]!
    const canvas = document.createElement('canvas')
    canvas.width = 32; canvas.height = 32
    const context = canvas.getContext('2d')!
    context.fillStyle = '#227c70'; context.fillRect(0, 0, 32, 32)
    const blob = await new Promise<Blob>(resolve => canvas.toBlob(blob => resolve(blob!), 'image/png'))
    request.resolve({ status: 'ok', jobId: request.jobId, bytes: await blob.arrayBuffer(), cacheControl: 'no-store' })
  }, index)
}

test('成功推送自动取图，重复推送和直接调用去重，PNG预览离页恢复不重复获取', async ({ page }) => {
  await setup(page)
  await push(page, 'SUCCEEDED')
  await expect(page.getByRole('status')).toContainText('正在获取 PNG 图片')
  await push(page, 'SUCCEEDED')
  await page.evaluate(async () => {
    const modulePath = '/src/stores/screenshotTrackingStore.ts'
    const { useScreenshotTrackingStore } = await import(modulePath)
    await useScreenshotTrackingStore().loadScreenshotResult()
  })
  expect(await resultCount(page)).toBe(1)
  await finishPng(page, 0)
  const image = page.getByAltText('网页截图预览', { exact: true })
  await expect(image).toBeVisible()
  await expect.poll(() => image.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(32)
  await expect(page.getByRole('button', { name: '另存为 PNG' })).toBeVisible()
  await expect(page.locator('.playground-screenshot-note')).toContainText('切换页面后仍可预览')
  await push(page, 'SUCCEEDED')
  await page.getByRole('link', { name: 'Applications', exact: true }).click()
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await expect(image).toBeVisible()
  await expect.poll(() => image.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(32)
  expect(await resultCount(page)).toBe(1)
})

test('获取失败后重复成功推送不自动重试，按钮手动重试并展示PNG', async ({ page }) => {
  await setup(page)
  await push(page, 'SUCCEEDED')
  await page.evaluate(() => {
    (window as unknown as TestWindow).__desktopDisplay.results[0]!.resolve({ status: 'failed', code: 'FETCH_FAILED' })
  })
  await expect(page.getByText('PNG 图片获取失败', { exact: true })).toBeVisible()
  await expect(page.locator('.playground-response')).toContainText('截图任务已完成')
  await push(page, 'SUCCEEDED')
  expect(await resultCount(page)).toBe(1)
  await page.getByRole('button', { name: '重试获取 PNG' }).click()
  await expect(page.getByRole('status')).toContainText('正在获取 PNG 图片')
  expect(await resultCount(page)).toBe(2)
  await finishPng(page, 1)
  await expect(page.getByAltText('网页截图预览', { exact: true })).toBeVisible()
  await expect(page.getByText('PNG 图片获取失败', { exact: true })).toHaveCount(0)
})

for (const late of ['success', 'failure', 'rejection'] as const) {
  test(`清理后重新跟踪同Job ID，旧请求${late}及finally不覆盖新请求`, async ({ page }) => {
    await setup(page)
    await push(page, 'SUCCEEDED')
    expect(await resultCount(page)).toBe(1)
    await page.evaluate(() => {
      const c = (window as unknown as TestWindow).__desktopDisplay
      c.snapshot = { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null }
      c.listener?.(c.snapshot)
    })
    await push(page, 'SUCCEEDED')
    expect(await resultCount(page)).toBe(2)
    if (late === 'success') await finishPng(page, 0)
    else await page.evaluate(late => {
      const request = (window as unknown as TestWindow).__desktopDisplay.results[0]!
      if (late === 'failure') request.resolve({ status: 'failed', code: 'FETCH_FAILED' })
      else request.reject()
    }, late)
    await expect(page.getByRole('status')).toContainText('正在获取 PNG 图片')
    await expect(page.getByAltText('网页截图预览', { exact: true })).toHaveCount(0)
    await expect(page.getByText('PNG 图片获取失败', { exact: true })).toHaveCount(0)
    await finishPng(page, 1)
    await expect(page.getByAltText('网页截图预览', { exact: true })).toBeVisible()
  })
}
