import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { installMockApi, signIn } from './support/mockApi'

const fakeKey = 'PLAYWRIGHT_ONLY_SCREENSHOT_KEY'
const articleUrl = 'https://blog.csdn.net/test/article/details/123'
type TrackedWindow = Window & { __screenshotUrls: { created: string[]; revoked: string[] } }

async function openScreenshot(page: Page, mode: 'sync' | 'async' = 'sync') {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await expect(page).toHaveURL(/endpoint=web-screenshot$/)
  if (mode === 'sync') await page.getByRole('button', { name: /同步截图/ }).click()
}
async function fillRequest(page: Page) {
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByLabel('网页 URL', { exact: true }).fill(articleUrl)
}
async function createPng(page: Page): Promise<Buffer> {
  const base64 = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 1280; canvas.height = 720
    const context = canvas.getContext('2d')!
    context.fillStyle = '#f8f9f5'; context.fillRect(0, 0, 1280, 720)
    context.fillStyle = '#227c70'; context.fillRect(80, 70, 1120, 70)
    context.fillStyle = '#ffffff'; context.font = '30px sans-serif'
    context.fillText('Nexus screenshot sample', 110, 116)
    context.fillStyle = '#274e43'; context.font = '24px sans-serif'
    context.fillText('Public page captured as PNG', 80, 210)
    return canvas.toDataURL('image/png').split(',')[1]!
  })
  return Buffer.from(base64, 'base64')
}
async function trackObjectUrls(page: Page) {
  await page.addInitScript(() => {
    const urls = { created: [] as string[], revoked: [] as string[] }
    ;(window as unknown as TrackedWindow).__screenshotUrls = urls
    const create = URL.createObjectURL.bind(URL)
    const revoke = URL.revokeObjectURL.bind(URL)
    URL.createObjectURL = (blob) => { const url = create(blob); urls.created.push(url); return url }
    URL.revokeObjectURL = (url) => { urls.revoked.push(url); revoke(url) }
  })
}
async function wasRevoked(page: Page, url: string) {
  return page.evaluate((value) => (window as unknown as TrackedWindow).__screenshotUrls.revoked.includes(value), url)
}
async function showPng(page: Page, png: Buffer, headers: Record<string, string> = {}) {
  await page.route('**/v1/web/screenshot', (route) => route.fulfill({
    contentType: 'image/png', body: png,
    headers: { 'Cache-Control': 'no-store', 'X-Nexus-Blocked-Resources': '0', 'X-Nexus-Resource-Limit-Reached': 'false', ...headers },
  }))
  await fillRequest(page)
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(page.getByRole('img', { name: '网页截图预览', exact: true })).toBeVisible()
}

const asyncJobId = '11111111-2222-4333-8444-555555555555'
const jobTime = '2026-10-23T08:08:24Z'

function screenshotJob(status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED', overrides: Record<string, unknown> = {}) {
  return {
    jobId: asyncJobId, status, attemptCount: status === 'QUEUED' ? 0 : 1,
    resultSizeBytes: status === 'SUCCEEDED' ? 12_345 : null,
    resultSha256: status === 'SUCCEEDED' ? 'a'.repeat(64) : null,
    blockedResources: status === 'SUCCEEDED' ? 2 : null,
    resourceLimitReached: status === 'SUCCEEDED' ? false : null,
    resultExpiresAt: status === 'SUCCEEDED' ? '2026-10-23T09:08:24Z' : null,
    failureCode: status === 'FAILED' ? 'WORKER_INTERRUPTED' : null,
    startedAt: status === 'QUEUED' ? null : jobTime,
    completedAt: ['SUCCEEDED', 'FAILED', 'EXPIRED'].includes(status) ? '2026-10-23T08:08:30Z' : null,
    createdAt: jobTime, updatedAt: jobTime, ...overrides,
  }
}

async function routeAsyncSubmission(page: Page) {
  await page.route('**/v1/web/screenshot-jobs', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(route.request().headers().cookie).toBeUndefined()
    expect(route.request().postDataJSON()).toEqual({ url: articleUrl })
    await route.fulfill({
      status: 201,
      headers: { Location: `/v1/web/screenshot-jobs/${asyncJobId}` },
      json: { code: 'SUCCESS', message: '截图任务已创建', data: {
        jobId: asyncJobId, status: 'QUEUED', attemptCount: 0, createdAt: jobTime, updatedAt: jobTime,
      } },
    })
  })
}

test('PNG 成功使用 ApiKey 且不带 Cookie，预览放大下载与两种示例正确', async ({ page }) => {
  await openScreenshot(page)
  await page.context().addCookies([{ name: 'unused-session', value: 'must-not-be-sent', url: 'http://127.0.0.1:4173' }])
  const png = await createPng(page)
  let count = 0
  await page.route('**/v1/web/screenshot', async (route) => {
    count++
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(route.request().headers().cookie).toBeUndefined()
    expect(route.request().postDataJSON()).toEqual({ url: articleUrl })
    await route.fulfill({ contentType: 'image/png', body: png, headers: {
      'Cache-Control': 'no-store', 'X-Nexus-Blocked-Resources': '0', 'X-Nexus-Resource-Limit-Reached': 'false',
    } })
  })
  await fillRequest(page)
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(page.locator('.playground-status')).toContainText('200 OK')
  const image = page.getByRole('img', { name: '网页截图预览', exact: true })
  await expect(image).toBeVisible()
  await expect.poll(() => image.evaluate((element) => (element as HTMLImageElement).naturalWidth)).toBe(1280)
  await expect(page.locator('.playground-screenshot-footer')).toContainText('1280 × 720')
  await expect(page.locator('.playground-response .playground-code')).toHaveCount(0)
  await expect(page.locator('.playground-screenshot-warning')).toHaveCount(0)
  await page.getByRole('button', { name: '放大预览', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '网页截图大图预览' })).toBeVisible()
  await expect(page.getByRole('img', { name: '网页截图大图', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 PNG', exact: true }).click()
  const download = await downloaded
  expect(download.suggestedFilename()).toBe('nexus-screenshot.png')
  expect(await readFile((await download.path())!)).toEqual(png)
  await page.getByRole('tab', { name: '响应信息', exact: true }).click()
  await expect(page.locator('.playground-screenshot-info')).toContainText('image/png')
  await expect(page.locator('.playground-screenshot-info')).toContainText('未触达；不代表资源全部完整')
  await expect(page.locator('.playground-screenshot-info')).toContainText('no-store')
  await page.getByRole('tab', { name: '截图预览', exact: true }).click()
  await expect(page.locator('.playground-example-code')).toContainText('Authorization: ApiKey <完整密钥>')
  await expect(page.locator('.playground-example-code')).toContainText('--output screenshot.png')
  await expect(page.locator('.playground-example-code')).not.toContainText('curl -i')
  await page.getByRole('button', { name: 'PowerShell', exact: true }).click()
  await expect(page.locator('.playground-example-code')).toContainText('ApiKey $apiKey')
  await expect(page.locator('.playground-example-code')).toContainText('Invoke-WebRequest')
  await expect(page.locator('.playground-example-code')).toContainText('-OutFile')
  await expect(page.locator('.playground-example-code')).not.toContainText('Bearer')
  await expect(page.locator('.playground-example-code')).not.toContainText(fakeKey)
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: '复制示例', exact: true }).click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('ApiKey $apiKey')
  expect(await page.evaluate((key) => JSON.stringify({ ...localStorage, ...sessionStorage }).includes(key), fakeKey)).toBe(false)
  expect(count).toBe(1)
  await page.locator('.console-scroll-area > .el-scrollbar__wrap').evaluate((element) => { element.scrollTop = 0 })
  await page.screenshot({ path: test.info().outputPath('screenshot-desktop.png') })
})

test('异步任务默认选中，创建后立即查询并在成功时只下载一次 PNG', async ({ page }) => {
  await openScreenshot(page, 'async')
  await expect(page.getByRole('button', { name: /^异步任务 / })).toHaveAttribute('aria-pressed', 'true')
  await page.context().addCookies([{ name: 'unused-session', value: 'must-not-be-sent', url: 'http://127.0.0.1:4173' }])
  await routeAsyncSubmission(page)
  const png = await createPng(page)
  let statusCalls = 0
  let resultCalls = 0
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}`, async (route) => {
    statusCalls++
    expect(route.request().method()).toBe('GET')
    expect(route.request().headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(route.request().headers().cookie).toBeUndefined()
    await route.fulfill({ json: { code: 'SUCCESS', message: '截图任务查询成功', data: screenshotJob('SUCCEEDED') } })
  })
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}/result`, async (route) => {
    resultCalls++
    expect(route.request().headers().authorization).toBe(`ApiKey ${fakeKey}`)
    await route.fulfill({ contentType: 'image/png', body: png, headers: { 'Cache-Control': 'no-store' } })
  })
  await fillRequest(page)
  await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
  await expect(page.locator('.playground-job-card')).toContainText('截图任务已完成')
  await expect(page.getByRole('img', { name: '网页截图预览', exact: true })).toBeVisible()
  await expect(page.locator('.playground-screenshot-footer')).toContainText('2 个资源未加载')
  expect(statusCalls).toBe(1)
  expect(resultCalls).toBe(1)
  await expect(page.locator('.playground-example-code')).toContainText('/v1/web/screenshot-jobs')
  await expect(page.locator('.playground-example-code')).toContainText('<Job ID>/result')
})

test('异步排队任务在上一次查询完成后等待约 2 秒，失败后停止且不请求 PNG', async ({ page }) => {
  await openScreenshot(page, 'async')
  await routeAsyncSubmission(page)
  const callTimes: number[] = []
  let resultCalls = 0
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}`, async (route) => {
    callTimes.push(Date.now())
    const data = callTimes.length === 1 ? screenshotJob('QUEUED') : screenshotJob('FAILED', { attemptCount: 2 })
    await route.fulfill({ json: { code: 'SUCCESS', message: '截图任务查询成功', data } })
  })
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}/result`, async (route) => { resultCalls++; await route.abort() })
  await fillRequest(page)
  await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
  await expect(page.locator('.playground-job-card')).toContainText('执行失败', { timeout: 5_000 })
  await expect(page.locator('.playground-job-details')).toContainText('WORKER_INTERRUPTED')
  await expect(page.locator('.playground-result-message')).toHaveClass(/is-error/)
  expect(callTimes).toHaveLength(2)
  expect(callTimes[1]! - callTimes[0]!).toBeGreaterThanOrEqual(1_800)
  expect(resultCalls).toBe(0)
})

test('异步状态查询网络失败保留 Job ID，手动继续后可取得结果', async ({ page }) => {
  await openScreenshot(page, 'async')
  await routeAsyncSubmission(page)
  const png = await createPng(page)
  let statusCalls = 0
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}`, async (route) => {
    statusCalls++
    if (statusCalls === 1) { await route.abort('connectionrefused'); return }
    await route.fulfill({ json: { code: 'SUCCESS', message: '截图任务查询成功', data: screenshotJob('SUCCEEDED') } })
  })
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}/result`, (route) => route.fulfill({ contentType: 'image/png', body: png }))
  await fillRequest(page)
  await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
  await expect(page.locator('.playground-job-poll-error')).toContainText('Job ID 已保留')
  await expect(page.locator('.playground-job-id')).toContainText(asyncJobId)
  await page.getByRole('button', { name: '继续查询', exact: true }).click()
  await expect(page.getByRole('img', { name: '网页截图预览', exact: true })).toBeVisible()
  expect(statusCalls).toBe(2)
})

test('异步任务排队时切换 API 会取消后续轮询', async ({ page }) => {
  await openScreenshot(page, 'async')
  await routeAsyncSubmission(page)
  let statusCalls = 0
  await page.route(`**/v1/web/screenshot-jobs/${asyncJobId}`, async (route) => {
    statusCalls++
    await route.fulfill({ json: { code: 'SUCCESS', message: '截图任务查询成功', data: screenshotJob('QUEUED') } })
  })
  await fillRequest(page)
  await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
  await expect.poll(() => statusCalls).toBe(1)
  await page.getByRole('button', { name: '生成 UUID', exact: true }).click()
  await page.waitForTimeout(2_200)
  expect(statusCalls).toBe(1)
})

test('次数上限和资源获取失败分别提示部分截图，200 不意味着资源完整', async ({ page }) => {
  await openScreenshot(page)
  const png = await createPng(page)
  await showPng(page, png, { 'X-Nexus-Blocked-Resources': '58', 'X-Nexus-Resource-Limit-Reached': 'true' })
  await expect(page.locator('.playground-screenshot-warning')).toContainText('已返回部分截图')
  await expect(page.locator('.playground-screenshot-footer')).toContainText('58 个资源未加载')
  await page.getByRole('tab', { name: '响应信息', exact: true }).click()
  await expect(page.locator('.playground-screenshot-info')).toContainText('已触达，返回部分截图')
  await page.unroute('**/v1/web/screenshot')
  await showPng(page, png, { 'X-Nexus-Blocked-Resources': '2' })
  await expect(page.locator('.playground-screenshot-warning')).toContainText('部分资源未加载')
  await expect(page.locator('.playground-screenshot-warning')).not.toContainText('已停止加载后续资源')
})

test('401/403 和业务错误保留真实 JSON，清理旧 PNG 且不影响管理登录', async ({ page }) => {
  await trackObjectUrls(page)
  await openScreenshot(page)
  await showPng(page, await createPng(page))
  const oldUrl = (await page.getByRole('img', { name: '网页截图预览', exact: true }).getAttribute('src'))!
  await page.unroute('**/v1/web/screenshot')
  let status = 401
  let code = 'INVALID_API_KEY_CREDENTIAL'
  await page.route('**/v1/web/screenshot', (route) => route.fulfill({ status, json: { code, message: fakeKey, data: null } }))
  for (const [nextStatus, nextCode] of [[401, 'INVALID_API_KEY_CREDENTIAL'], [403, 'API_KEY_FORBIDDEN'],
    [400, 'INVALID_SCREENSHOT_REQUEST'], [422, 'SCREENSHOT_LIMIT_EXCEEDED'], [502, 'SCREENSHOT_PAGE_FETCH_FAILED'],
    [503, 'SCREENSHOT_BUSY'], [503, 'SCREENSHOT_ENGINE_UNAVAILABLE'], [504, 'SCREENSHOT_TIMEOUT']] as const) {
    status = nextStatus; code = nextCode
    await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
    await expect(page.locator('.playground-status')).toContainText(String(status))
    await expect(page.locator('.playground-response .playground-code')).toContainText(code)
    await expect(page.locator('.playground-result-message')).toHaveClass(/is-error/)
    await expect(page.locator('.playground-screenshot-result')).toHaveCount(0)
    await expect(page.locator('.playground-response')).not.toContainText(fakeKey)
  }
  expect(await wasRevoked(page, oldUrl)).toBe(true)
  await page.getByRole('link', { name: 'Applications', exact: true }).click()
  await expect(page).toHaveURL(/\/applications$/)
})

test('缺少 Key 和不安全或非白名单 URL 在发送前拒绝', async ({ page }) => {
  await openScreenshot(page)
  let count = 0
  await page.route('**/v1/web/screenshot', async (route) => { count++; await route.abort() })
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(page.locator('#playground-key-error')).toContainText('请输入完整 API Key')
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  for (const url of ['', 'http://blog.csdn.net/a', 'https://blog.csdn.net.evil.example/a', 'https://blog.csdn.net:444/a',
    'https://user@blog.csdn.net/a', 'https://blog.csdn.net/a#part', 'https://127.0.0.1/a', 'https://www.google.com/search?q=java']) {
    await page.getByLabel('网页 URL', { exact: true }).fill(url)
    await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
    await expect(page.locator('.playground-form-error')).toBeVisible()
  }
  expect(count).toBe(0)
})

test('伪装 PNG、HTML/SVG 和超大响应不作为可执行预览，损坏图片给出解码提示', async ({ page }) => {
  await openScreenshot(page)
  await fillRequest(page)
  const validPng = await createPng(page)
  const oversized = Buffer.alloc(5 * 1024 * 1024 + 1)
  validPng.copy(oversized, 0, 0, 8)
  let response: { contentType: string; body: Buffer } = { contentType: 'image/svg+xml', body: Buffer.from('<svg onload="window.__screenshotXss=1"></svg>') }
  await page.route('**/v1/web/screenshot', (route) => route.fulfill(response))
  for (const next of [response, { contentType: 'text/html', body: Buffer.from('<script>window.__screenshotXss=1</script>') },
    { contentType: 'image/png', body: Buffer.from('not a PNG') }, { contentType: 'image/png', body: oversized },
    { contentType: 'application/json', body: Buffer.from(JSON.stringify({ code: 'SUCCESS', data: fakeKey })) }]) {
    response = next
    await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
    await expect(page.locator('.playground-result-message')).toContainText('服务器没有返回有效的 PNG')
    await expect(page.locator('.playground-screenshot-result')).toHaveCount(0)
    await expect(page.locator('.playground-response')).not.toContainText(fakeKey)
  }
  response = { contentType: 'image/png', body: validPng.subarray(0, 8) }
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(page.locator('.playground-image-error')).toContainText('图片无法解码')
  await expect(page.getByRole('button', { name: '下载 PNG', exact: true })).toBeDisabled()
  expect(await page.evaluate(() => (window as Window & { __screenshotXss?: number }).__screenshotXss)).toBeUndefined()
})

test('缺失或非法计数头不显示 NaN，也不伪造后端提供的元数据', async ({ page }) => {
  await openScreenshot(page)
  const png = await createPng(page)
  await page.route('**/v1/web/screenshot', (route) => route.fulfill({ contentType: 'image/png', body: png,
    headers: { 'X-Nexus-Blocked-Resources': 'NaN' } }))
  await fillRequest(page)
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(page.getByRole('img', { name: '网页截图预览', exact: true })).toBeVisible()
  await expect(page.locator('.playground-screenshot-warning')).toHaveCount(0)
  await page.getByRole('tab', { name: '响应信息', exact: true }).click()
  await expect(page.locator('.playground-screenshot-info')).toContainText('后端未提供')
  await expect(page.locator('.playground-screenshot-info')).not.toContainText('NaN')
})

test('网络失败有提示，切换 API 取消未完成请求且不接受过时 PNG', async ({ page }) => {
  await trackObjectUrls(page)
  await openScreenshot(page)
  await fillRequest(page)
  await page.route('**/v1/web/screenshot', (route) => route.abort('connectionrefused'))
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(page.locator('.playground-result-message')).toContainText('无法连接服务器')
  await page.unroute('**/v1/web/screenshot')
  const png = await createPng(page)
  let release: (() => void) | undefined
  const held = new Promise<void>((resolve) => { release = resolve })
  let count = 0
  await page.route('**/v1/web/screenshot', async (route) => {
    count++
    await held
    await route.fulfill({ contentType: 'image/png', body: png })
  })
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect.poll(() => count).toBe(1)
  await expect(page.getByRole('button', { name: '发送中…', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '生成 UUID', exact: true }).click()
  release?.()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await expect(page.locator('.playground-response-empty')).toBeVisible()
  await expect(page.locator('.playground-screenshot-result')).toHaveCount(0)
  expect(await page.evaluate(() => (window as unknown as TrackedWindow).__screenshotUrls.created)).toHaveLength(0)
})

test('重试、切换和离开页面释放 Blob URL，弹窗不残留且 Key 不持久化', async ({ page }) => {
  await trackObjectUrls(page)
  await openScreenshot(page)
  await showPng(page, await createPng(page))
  const image = page.getByRole('img', { name: '网页截图预览', exact: true })
  const first = (await image.getAttribute('src'))!
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(image).toBeVisible()
  await expect.poll(async () => image.getAttribute('src')).not.toBe(first)
  expect(await wasRevoked(page, first)).toBe(true)
  const second = (await image.getAttribute('src'))!
  await page.getByRole('button', { name: '计算 Hash', exact: true }).click()
  await expect(page).toHaveURL(/endpoint=hash$/)
  expect(await wasRevoked(page, second)).toBe(true)
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await page.getByRole('button', { name: '生成同步截图', exact: true }).click()
  await expect(image).toBeVisible()
  const third = (await image.getAttribute('src'))!
  await page.getByRole('button', { name: '放大预览', exact: true }).click()
  // 弹窗会正常拦截背景点击；浏览器后退才能模拟弹窗仍打开时离开页面。
  await page.goBack()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await wasRevoked(page, third)).toBe(true)
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await expect(page.getByLabel('API Key', { exact: true })).toHaveValue('')
  await page.getByRole('link', { name: '查看接口文档' }).click()
  await expect(page).toHaveURL(/\/docs\/web-screenshot$/)
  await page.getByRole('link', { name: '在线试用此接口' }).click()
  await expect(page.getByLabel('API Key', { exact: true })).toHaveValue('')
  expect(await page.evaluate((key) => JSON.stringify({ ...localStorage, ...sessionStorage }).includes(key), fakeKey)).toBe(false)
})

test('截图教程与在线入口一致，窄屏预览和放大无横向溢出', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: '开发文档', exact: true }).click()
  await page.locator('.docs-card').filter({ hasText: '网页截图' }).click()
  await expect(page.locator('.docs-markdown')).toContainText('Authorization: ApiKey <完整密钥>')
  await expect(page.locator('.docs-markdown')).toContainText('X-Nexus-Resource-Limit-Reached')
  await page.getByRole('link', { name: '在线试用此接口' }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('button', { name: '网页截图', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: /同步截图/ }).click()
  await showPng(page, await createPng(page), { 'X-Nexus-Blocked-Resources': '58', 'X-Nexus-Resource-Limit-Reached': 'true' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const gap = await page.locator('.playground-workspace').evaluate((workspace) => {
    const request = workspace.querySelector('.playground-request')!.getBoundingClientRect()
    const response = workspace.querySelector('.playground-response')!.getBoundingClientRect()
    return response.top - request.bottom
  })
  expect(gap).toBeGreaterThanOrEqual(16)
  await page.locator('.playground-screenshot-result').scrollIntoViewIfNeeded()
  await page.screenshot({ path: test.info().outputPath('screenshot-mobile.png') })
  await page.getByRole('button', { name: '放大预览', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '请求示例', exact: true }).click()
  await expect(page.locator('.playground-example-code')).toContainText('Authorization: ApiKey')
})
