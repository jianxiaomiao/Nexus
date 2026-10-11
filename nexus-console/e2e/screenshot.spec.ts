import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { installMockApi, signIn } from './support/mockApi'

const fakeKey = 'PLAYWRIGHT_ONLY_SCREENSHOT_KEY'
const articleUrl = 'https://blog.csdn.net/test/article/details/123'
type TrackedWindow = Window & { __screenshotUrls: { created: string[]; revoked: string[] } }

async function openScreenshot(page: Page) {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await expect(page).toHaveURL(/endpoint=web-screenshot$/)
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
  await expect(page.getByRole('img', { name: '网页截图预览', exact: true })).toBeVisible()
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
    await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
  await expect(page.locator('#playground-key-error')).toContainText('请输入完整 API Key')
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  for (const url of ['', 'http://blog.csdn.net/a', 'https://blog.csdn.net.evil.example/a', 'https://blog.csdn.net:444/a',
    'https://user@blog.csdn.net/a', 'https://blog.csdn.net/a#part', 'https://127.0.0.1/a', 'https://www.google.com/search?q=java']) {
    await page.getByLabel('网页 URL', { exact: true }).fill(url)
    await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
    await page.getByRole('button', { name: '生成截图', exact: true }).click()
    await expect(page.locator('.playground-result-message')).toContainText('服务器没有返回有效的 PNG')
    await expect(page.locator('.playground-screenshot-result')).toHaveCount(0)
    await expect(page.locator('.playground-response')).not.toContainText(fakeKey)
  }
  response = { contentType: 'image/png', body: validPng.subarray(0, 8) }
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
  await expect(image).toBeVisible()
  await expect.poll(async () => image.getAttribute('src')).not.toBe(first)
  expect(await wasRevoked(page, first)).toBe(true)
  const second = (await image.getAttribute('src'))!
  await page.getByRole('button', { name: '计算 Hash', exact: true }).click()
  await expect(page).toHaveURL(/endpoint=hash$/)
  expect(await wasRevoked(page, second)).toBe(true)
  await page.getByRole('button', { name: '网页截图', exact: true }).click()
  await page.getByRole('button', { name: '生成截图', exact: true }).click()
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
