import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

// 独立学习实验：不使用 test()、不启动 Nexus、不接触数据库或密钥。
// 所有目标都是本脚本创建的本地样本。此处本地资源许可不能作为生产 SSRF 策略。
const outputDir = fileURLToPath(new URL('../test-results/browser-routing-experiment/', import.meta.url))
const modes = [
  { id: '01-entry-only', title: '只检查入口', description: '入口检查通过后，浏览器自行请求页面和所有资源。', strategy: 'entry' },
  { id: '02-abort-private', title: '逐个拦截资源', description: '正常资源交给浏览器请求，模拟内网资源被 abort。', strategy: 'abort' },
  { id: '03-fetch-and-fulfill', title: 'Fetcher 获取，浏览器渲染', description: '允许的资源由 Fetcher 获取，再通过 fulfill 交给浏览器。', strategy: 'fulfill' },
]
const fixtureRequests = []
const privateRequests = []
const trace = []
const results = []
let currentMode
let fixtureOrigin
let privateOrigin
let browser

const css = `
  * { box-sizing: border-box; }
  body { margin: 0; background: #f5f7f3; color: #193e35; font-family: system-ui, sans-serif; }
  main { max-width: 940px; margin: 32px auto; padding: 32px; border: 1px solid #d7e4dd; border-radius: 18px; background: white; }
  h1 { margin: 10px 0 14px; font-size: 30px; }
  h2 { font-size: 18px; margin: 0 0 12px; }
  p { line-height: 1.7; }
  .eyebrow { color: #26776a; font-size: 12px; letter-spacing: .12em; }
  .css-proof { background: #e6f6ec; border-left: 5px solid #26866a; padding: 14px 18px; border-radius: 6px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-top: 22px; }
  section { min-width: 0; padding: 20px; border: 1px solid #d7e4dd; border-radius: 10px; }
  #allowed-image { display: block; width: 100%; height: 120px; object-fit: contain; margin-bottom: 12px; }
  .status { padding: 10px 0; font-size: 14px; }
  #private-image { max-height: 30px; }
  #run-result { margin: 22px 0 0; padding: 18px; border-radius: 8px; background: #193e35; color: #eff9f3; white-space: pre-line; line-height: 1.8; }
  footer { color: #667f73; font-size: 12px; line-height: 1.6; margin-top: 18px; }
`
const diagram = `<svg xmlns="http://www.w3.org/2000/svg" width="500" height="150" viewBox="0 0 500 150">
  <rect x="5" y="15" width="490" height="120" rx="18" fill="#edf5fc"/>
  <rect x="35" y="48" width="120" height="55" rx="9" fill="#3478bb"/>
  <rect x="190" y="48" width="120" height="55" rx="9" fill="#27866a"/>
  <rect x="345" y="48" width="120" height="55" rx="9" fill="#3478bb"/>
  <g font-family="sans-serif" font-size="19" text-anchor="middle" fill="white"><text x="95" y="82">IMAGE</text><text x="250" y="82">LOADED</text><text x="405" y="82">OK</text></g>
  <path d="M158 76 H185 M313 76 H340" stroke="#486b83" stroke-width="3"/>
</svg>`

function article() {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>浏览器请求拦截实验</title>
    <link rel="icon" href="data:,"><link rel="stylesheet" href="/style.css"><script defer src="/app.js"></script></head>
    <body><main><div class="eyebrow">NEXUS · 独立学习实验</div><h1>${currentMode.title}</h1><p>${currentMode.description}</p>
    <div class="css-proof">这个绿色提示框由外部 CSS 绘制，说明样式资源已加载。</div>
    <div class="grid"><section><h2>允许的页面资源</h2><img id="allowed-image" src="/image.svg" alt="正常样本图片">
    <div class="status">JavaScript：<strong id="script-result">尚未执行</strong></div><p>HTML、CSS、图片、脚本共四个正常资源。</p></section>
    <section><h2>模拟内网请求</h2><img id="private-image" alt="模拟内网图片">
    <div class="status">图片请求：<strong id="image-result">等待结果</strong></div>
    <div class="status">脚本 fetch：<strong id="fetch-result">等待结果</strong></div><p>这两次请求都由网页触发。</p></section></div>
    <aside id="run-result">正在核对服务端实际收到的请求…</aside>
    <footer>目标都是本脚本创建的本地服务，不访问真实内网业务、不连接 Nexus 数据库。实验演示请求控制，不证明生产 DNS 或完整浏览器出站隔离。</footer>
    </main></body></html>`
}

function pageScript() {
  return `
    document.querySelector('#script-result').textContent = '已执行';
    const image = document.querySelector('#private-image');
    image.addEventListener('load', () => { document.querySelector('#image-result').textContent = '请求成功'; });
    image.addEventListener('error', () => { document.querySelector('#image-result').textContent = '请求被拦截'; });
    image.src = ${JSON.stringify(`${privateOrigin}/private-pixel`)};
    fetch(${JSON.stringify(`${privateOrigin}/private-data`)})
      .then(response => response.json())
      .then(() => { document.querySelector('#fetch-result').textContent = '请求成功'; })
      .catch(() => { document.querySelector('#fetch-result').textContent = '请求被拦截'; });
  `
}

function respond(response, contentType, body) {
  response.writeHead(200, { 'Content-Type': contentType, 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' })
  response.end(body)
}

const fixtures = createServer((request, response) => {
  fixtureRequests.push({ mode: currentMode.id, path: request.url, client: request.headers['user-agent'] === 'Nexus-Lab-Fetcher/1.0' ? 'fetcher' : 'browser' })
  if (request.url === '/article') respond(response, 'text/html; charset=utf-8', article())
  else if (request.url === '/style.css') respond(response, 'text/css; charset=utf-8', css)
  else if (request.url === '/image.svg') respond(response, 'image/svg+xml', diagram)
  else if (request.url === '/app.js') respond(response, 'application/javascript; charset=utf-8', pageScript())
  else { response.writeHead(404); response.end() }
})
const privateService = createServer((request, response) => {
  privateRequests.push({ mode: currentMode.id, path: request.url })
  if (request.url === '/private-pixel') respond(response, 'image/svg+xml', diagram)
  else respond(response, 'application/json', JSON.stringify({ message: '本地哨兵收到了请求' }))
})

async function listen(server) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  return `http://127.0.0.1:${server.address().port}`
}

function isAllowedLabResource(rawUrl) {
  const url = new URL(rawUrl)
  // 仅放行本实验自己创建的资源服务器和四个固定路径，不是生产公网地址规则。
  return url.origin === fixtureOrigin && !url.search && new Set(['/article', '/style.css', '/image.svg', '/app.js']).has(url.pathname)
}

async function labFetcher(url) {
  assert(isAllowedLabResource(url), 'Lab Fetcher received a denied target')
  const response = await fetch(url, { redirect: 'error', headers: { 'User-Agent': 'Nexus-Lab-Fetcher/1.0' }, signal: AbortSignal.timeout(3000) })
  assert.equal(response.status, 200)
  const body = Buffer.from(await response.arrayBuffer())
  assert(body.byteLength < 64 * 1024, 'Lab fixture exceeds size budget')
  return { status: response.status, contentType: response.headers.get('content-type'), body }
}

try {
  await mkdir(outputDir, { recursive: true })
  fixtureOrigin = await listen(fixtures)
  privateOrigin = await listen(privateService)
  browser = await chromium.launch({ headless: true })
  for (const mode of modes) {
    currentMode = mode
    const context = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1080, height: 850 } })
    try {
      const page = await context.newPage()
      const target = `${fixtureOrigin}/article`
      assert(isAllowedLabResource(target), 'Main document must pass entry check')
      if (mode.strategy !== 'entry') {
        await context.route('**/*', async (route) => {
          const request = route.request()
          const permitted = request.method() === 'GET' && isAllowedLabResource(request.url())
          const entry = { mode: mode.id, resourceType: request.resourceType(), path: new URL(request.url()).pathname, action: permitted ? mode.strategy : 'abort' }
          trace.push(entry)
          if (!permitted) { await route.abort(); return }
          if (mode.strategy === 'abort') { await route.continue(); return }
          // 用自己获取的响应满足浏览器请求，Chromium 不再自行获取这个资源。
          const resource = await labFetcher(request.url())
          await route.fulfill(resource)
        })
      }
      await page.goto(target, { waitUntil: 'load', timeout: 10_000 })
      await page.waitForFunction(() => ['请求成功', '请求被拦截'].includes(document.querySelector('#fetch-result')?.textContent)
        && ['请求成功', '请求被拦截'].includes(document.querySelector('#image-result')?.textContent))
      const observed = await page.evaluate(() => ({
        css: getComputedStyle(document.querySelector('.css-proof')).backgroundColor,
        imageLoaded: document.querySelector('#allowed-image').naturalWidth > 0,
        script: document.querySelector('#script-result').textContent,
        privateImage: document.querySelector('#image-result').textContent,
        privateFetch: document.querySelector('#fetch-result').textContent,
      }))
      assert.equal(observed.css, 'rgb(230, 246, 236)')
      assert.equal(observed.imageLoaded, true)
      assert.equal(observed.script, '已执行')
      const normal = fixtureRequests.filter(request => request.mode === mode.id)
      const internal = privateRequests.filter(request => request.mode === mode.id)
      const row = { scenario: mode.title, browserResourceRequests: normal.filter(request => request.client === 'browser').length,
        fetcherResourceRequests: normal.filter(request => request.client === 'fetcher').length,
        internalRequests: internal.length, ...observed }
      assert.equal(normal.length, 4)
      assert.equal(internal.length, mode.strategy === 'entry' ? 2 : 0)
      assert.equal(row.browserResourceRequests, mode.strategy === 'fulfill' ? 0 : 4)
      assert.equal(row.fetcherResourceRequests, mode.strategy === 'fulfill' ? 4 : 0)
      const expected = mode.strategy === 'entry' ? '请求成功' : '请求被拦截'
      assert.equal(observed.privateImage, expected)
      assert.equal(observed.privateFetch, expected)
      await page.locator('#run-result').evaluate((element, result) => {
        element.textContent = `实际观测：\n浏览器直接获取正常资源 ${result.browserResourceRequests} 次 · Fetcher 获取 ${result.fetcherResourceRequests} 次\n模拟内网服务收到 ${result.internalRequests} 次请求`
      }, row)
      await page.screenshot({ path: `${outputDir}/${mode.id}.png`, fullPage: true })
      results.push(row)
    } finally { await context.close() }
  }
  await writeFile(`${outputDir}/result.json`, JSON.stringify({ results, trace, fixtureRequests, privateRequests }, null, 2))
  console.table(results.map(({ scenario, browserResourceRequests, fetcherResourceRequests, internalRequests }) => ({ scenario, browserResourceRequests, fetcherResourceRequests, internalRequests })))
  console.log(`PASS: all three browser request-control scenarios; screenshots and result.json in ${outputDir}`)
} finally {
  await browser?.close()
  for (const server of [fixtures, privateService]) {
    server.closeAllConnections()
    if (server.listening) await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  }
}
