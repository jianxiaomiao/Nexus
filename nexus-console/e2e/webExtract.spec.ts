import { expect, test, type Page } from '@playwright/test'
import { installMockApi, signIn } from './support/mockApi'

const fakeKey = 'PLAYWRIGHT_ONLY_EXTRACT_KEY'
const articleUrl = 'https://blog.csdn.net/test/article/details/123'
const sample = {
  title: '网页提取示例',
  textContent: `第一段纯文本。第二段纯文本。${fakeKey}`,
  contentHtml: `<p>第一段正文。</p><img src="https://images.example.test/ok.png" alt="正常配图"><p>第二段正文。${fakeKey}</p><img src="https://images.example.test/denied.png" alt="受限配图">`,
}

async function openExtract(page: Page) {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: 'API 调试台' }).click()
  await page.getByRole('button', { name: '网页正文提取', exact: true }).click()
  await expect(page).toHaveURL(/endpoint=web-extract$/)
}

async function fillRequest(page: Page) {
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  await page.getByLabel('文章 URL', { exact: true }).fill(articleUrl)
}

test('提取只使用机器 Key，正文、图片、纯文本与 JSON 可切换且不泄露 Key', async ({ page }) => {
  await openExtract(page)
  await page.context().addCookies([{ name: 'unused-session', value: 'must-not-be-sent', url: 'http://127.0.0.1:4173' }])
  let count = 0
  await page.route('**/v1/web/extract', async (route) => {
    count++
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toBe(`ApiKey ${fakeKey}`)
    expect(route.request().headers().cookie).toBeUndefined()
    expect(route.request().postDataJSON()).toEqual({ url: articleUrl })
    await route.fulfill({ json: { code: 'SUCCESS', message: '内容提取成功', data: sample } })
  })
  await page.route('https://images.example.test/**', async (route) => {
    expect(route.request().headers().referer).toBeUndefined()
    if (route.request().url().endsWith('denied.png')) {
      await route.fulfill({ status: 403, body: '' })
    } else {
      await route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=', 'base64') })
    }
  })
  await fillRequest(page)
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await expect(page.locator('.playground-status')).toContainText('200 OK')
  await expect(page.getByRole('heading', { name: sample.title })).toBeVisible()
  await expect(page.getByRole('img', { name: '正常配图' })).toBeVisible()
  await expect(page.locator('.playground-image-error')).toContainText('可能受原站防盗链或网络限制')
  expect(await page.locator('.playground-article').evaluate((element) => Array.from(element.children).map((child) => child.tagName))).toEqual(['P', 'DIV', 'P', 'DIV'])
  await page.locator('.console-scroll-area > .el-scrollbar__wrap').evaluate((element) => { element.scrollTop = 0 })
  await page.screenshot({ path: test.info().outputPath('extract-desktop.png') })
  await page.getByRole('tab', { name: '纯文本', exact: true }).click()
  await expect(page.locator('.playground-article-text')).toContainText('第一段纯文本')
  await page.getByRole('tab', { name: 'JSON', exact: true }).click()
  await expect(page.locator('.playground-response .playground-code')).toContainText('contentHtml')
  await expect(page.locator('.playground-response')).not.toContainText(fakeKey)
  await page.getByRole('button', { name: 'PowerShell', exact: true }).click()
  await expect(page.locator('.playground-example-code')).toContainText('/v1/web/extract')
  await expect(page.locator('.playground-example-code')).toContainText('ApiKey $apiKey')
  await expect(page.locator('.playground-example-code')).not.toContainText(fakeKey)
  expect(await page.evaluate((key) => JSON.stringify({ ...localStorage, ...sessionStorage }).includes(key), fakeKey)).toBe(false)
  expect(count).toBe(1)
})

test('预览不会执行外站脚本、事件、iframe 或不安全图片 URL', async ({ page }) => {
  await openExtract(page)
  let unwantedRequests = 0
  await page.route('https://evil.example.test/**', async (route) => {
    unwantedRequests++
    await route.abort()
  })
  await page.route('**/v1/web/extract', async (route) => {
    await route.fulfill({ json: { code: 'SUCCESS', data: {
      title: '<img src=x onerror=alert(1)>', textContent: '<script>alert(1)</script>',
      contentHtml: '<p onclick="window.__extractXss=1" style="position:fixed">安全正文</p><script>window.__extractXss=1</script><iframe src="https://evil.example.test/frame"></iframe><img src="javascript:alert(1)"><a href="javascript:alert(1)">链接文本</a><svg onload="window.__extractXss=1"></svg>',
    } } })
  })
  await fillRequest(page)
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  const preview = page.locator('.playground-article')
  await expect(preview).toContainText('安全正文')
  await expect(preview).toContainText('链接文本')
  await expect(preview.locator('script,iframe,img,svg,a,[onclick],[style]')).toHaveCount(0)
  await preview.getByText('安全正文', { exact: true }).click()
  expect(await page.evaluate(() => (window as Window & { __extractXss?: number }).__extractXss)).toBeUndefined()
  await expect(page.locator('.playground-article-title')).toHaveText('<img src=x onerror=alert(1)>')
  expect(unwantedRequests).toBe(0)
})

test('缺少 Key 和非法 URL 在前端阻止发送，白名单域名不可用后缀伪装', async ({ page }) => {
  await openExtract(page)
  let count = 0
  await page.route('**/v1/web/extract', async (route) => { count++; await route.abort() })
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await expect(page.locator('#playground-key-error')).toContainText('请输入完整 API Key')
  await page.getByLabel('API Key', { exact: true }).fill(fakeKey)
  for (const url of ['', 'http://blog.csdn.net/a', 'https://blog.csdn.net.evil.example/a', 'https://blog.csdn.net:444/a', 'https://user@blog.csdn.net/a', 'https://blog.csdn.net/a#part', 'https://www.google.com/search?q=java']) {
    await page.getByLabel('文章 URL', { exact: true }).fill(url)
    await page.getByRole('button', { name: '发送请求', exact: true }).click()
    await expect(page.locator('.playground-form-error')).toBeVisible()
  }
  expect(count).toBe(0)
})

test('认证与业务失败保留真实 HTTP/JSON，失败不残留上次预览且管理登录仍有效', async ({ page }) => {
  await openExtract(page)
  let response: { status: number; code: string; data: unknown } = { status: 200, code: 'SUCCESS', data: { ...sample, contentHtml: '<p>上次正文</p>' } }
  await page.route('**/v1/web/extract', async (route) => {
    await route.fulfill({ status: response.status, json: { code: response.code, data: response.data } })
  })
  await fillRequest(page)
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await expect(page.locator('.playground-article')).toContainText('上次正文')
  for (const [status, code] of [[401, 'INVALID_API_KEY_CREDENTIAL'], [403, 'API_KEY_FORBIDDEN'], [400, 'INVALID_WEB_EXTRACT_REQUEST'], [422, 'WEB_CONTENT_UNAVAILABLE'], [502, 'WEB_PAGE_FETCH_FAILED']] as const) {
    response = { status, code, data: null }
    await page.getByRole('button', { name: '发送请求', exact: true }).click()
    await expect(page.locator('.playground-status')).toContainText(String(status))
    await expect(page.locator('.playground-response .playground-code')).toContainText(code)
    await expect(page.locator('.playground-article')).toHaveCount(0)
    await expect(page.locator('.playground-result-message')).toHaveClass(/is-error/)
  }
  await page.getByRole('link', { name: 'Applications', exact: true }).click()
  await expect(page).toHaveURL(/\/applications$/)
})

test('网络连接失败显示提示，切换 API 会取消请求并丢弃过时结果', async ({ page }) => {
  await openExtract(page)
  await fillRequest(page)
  await page.route('**/v1/web/extract', (route) => route.abort('connectionrefused'))
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await expect(page.locator('.playground-result-message')).toContainText('无法连接服务器')
  await page.unroute('**/v1/web/extract')
  let release: (() => void) | undefined
  const held = new Promise<void>((resolve) => { release = resolve })
  let started = false
  await page.route('**/v1/web/extract', async (route) => {
    started = true
    await held
    await route.fulfill({ json: { code: 'SUCCESS', data: { title: '过时结果', textContent: '旧正文', contentHtml: '<p>旧正文</p>' } } })
  })
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await expect(page.getByRole('button', { name: '发送中…', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '生成 UUID', exact: true }).click()
  await expect(page).toHaveURL(/endpoint=uuid$/)
  release?.()
  await page.getByRole('button', { name: '网页正文提取', exact: true }).click()
  await expect(page.locator('.playground-response')).not.toContainText('过时结果')
  await expect(page.locator('.playground-response-empty')).toBeVisible()
})

test('教程入口预选提取 Tab，窄屏无横向溢出且离开页面清除 Key', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  await page.getByRole('link', { name: '开发文档', exact: true }).click()
  await page.locator('.docs-card').filter({ hasText: '网页正文提取' }).click()
  await expect(page).toHaveURL(/\/docs\/web-extract$/)
  await expect(page.locator('.docs-markdown')).toContainText('服务端不下载图片')
  await expect(page.locator('.docs-markdown')).toContainText('WEB_CONTENT_UNAVAILABLE')
  await page.getByRole('link', { name: '在线试用此接口' }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('button', { name: '网页正文提取', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await fillRequest(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  // 两张卡片须在同一帧测量，避免浏览器自动滚动改变两次采样的坐标。
  const gap = await page.locator('.playground-workspace').evaluate((workspace) => {
    const request = workspace.querySelector('.playground-request')!.getBoundingClientRect()
    const response = workspace.querySelector('.playground-response')!.getBoundingClientRect()
    return response.top - request.bottom
  })
  expect(gap).toBeGreaterThanOrEqual(16)
  await page.locator('.console-scroll-area > .el-scrollbar__wrap').evaluate((element) => { element.scrollTop = 0 })
  await page.screenshot({ path: test.info().outputPath('extract-mobile.png') })
  await page.getByRole('link', { name: '查看接口文档' }).click()
  await expect(page).toHaveURL(/\/docs\/web-extract$/)
  await page.getByRole('link', { name: '在线试用此接口' }).click()
  await expect(page.getByLabel('API Key', { exact: true })).toHaveValue('')
})
