const fs = require('node:fs/promises')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const { createHash } = require('node:crypto')
const root = path.resolve(__dirname, '..')
const consoleRoot = path.resolve(root, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const { _electron, expect } = consoleRequire('@playwright/test')

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'resume-acceptance-'))
  let instance, vite, page, submitted = false, createdJobId = null
  try {
    const health = await fetch('http://127.0.0.1:8080/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}', signal: AbortSignal.timeout(3000),
    })
    if (health.status !== 400) throw Error('Local backend preflight failed')
    let live = false
    try {
      const response = await fetch('http://127.0.0.1:5178/', { signal: AbortSignal.timeout(1500) })
      live = response.ok && (await response.text()).includes('/src/main.ts')
    } catch {}
    if (!live) {
      const { createServer } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
      vite = await createServer({ root: consoleRoot, configFile: path.join(consoleRoot, 'vite.config.ts'), cacheDir: path.join(root, '.runtime/vite-cache'), server: { host: '127.0.0.1', port: 5178, strictPort: true, open: false } })
      await vite.listen()
    }
    const env = { ...process.env, NEXUS_RESUME_ACCEPTANCE_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'resume-acceptance-main.cjs')], env, timeout: 20000 })
    page = await instance.firstWindow()
    await page.waitForURL('**/login')
    console.log('WAITING: log in inside the isolated Nexus window; do not send credentials in chat.')
    await page.waitForURL('**/applications', { timeout: 15 * 60 * 1000 })
    await page.getByRole('link', { name: 'API 调试台' }).click()
    await page.getByRole('button', { name: '网页截图', exact: true }).click()
    console.log('WAITING: fill API Key and screenshot URL; script will submit once when both are filled. Do not click submit.')
    await expect.poll(() => page.evaluate(() => {
      const key = document.querySelector('#playground-key')
      const url = document.querySelector('#playground-screenshot-url')
      try {
        const target = new URL(url?.value?.trim())
        return !!key?.value?.trim() && target.protocol === 'https:' &&
          ['blog.csdn.net', 'zhuanlan.zhihu.com'].includes(target.hostname) &&
          !target.username && !target.password && !target.port && !target.hash && target.pathname.length > 1
      } catch { return false }
    }), { timeout: 15 * 60 * 1000, intervals: [1000] }).toBe(true)

    let postCount = 0, rendererStatusReads = 0
    page.on('request', request => {
      const url = new URL(request.url())
      if (request.method() === 'POST' && url.pathname === '/v1/web/screenshot-jobs') postCount++
      if (request.method() === 'GET' && /^\/v1\/web\/screenshot-jobs\//.test(url.pathname)) rendererStatusReads++
    })
    await page.evaluate(() => {
      window.__resumeAcceptanceUpdates = []
      window.__resumeAcceptanceDispose = window.nexusDesktop.onScreenshotTrackingUpdated(s => {
        window.__resumeAcceptanceUpdates.push({ jobId: s.jobId, traceStatus: s.traceStatus, jobStatus: s.latestJob?.status ?? null })
      })
    })
    const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname === '/v1/web/screenshot-jobs' && r.request().method() === 'POST')
    submitted = true
    await page.getByRole('button', { name: '创建异步任务', exact: true }).click()
    const createdResponse = await responsePromise
    const created = await createdResponse.json()
    if (!createdResponse.ok() || typeof created.data?.jobId !== 'string') throw Error('Real screenshot creation failed; no automatic resubmit')
    createdJobId = created.data.jobId
    const read = () => page.evaluate(() => window.nexusDesktop.readScreenshotTracking())
    await expect.poll(async () => (await read()).snapshot.traceStatus, { timeout: 15000 }).toBe('PAUSED')
    await expect(page.getByRole('button', { name: '继续查询', exact: true })).toBeEnabled()
    expect((await read()).snapshot.jobId).toBe(createdJobId)
    const paused = await instance.evaluate(() => globalThis.__resumeAcceptance.requests)
    expect(paused).toHaveLength(1)
    expect(paused[0].injected).toBe(true)
    expect(paused[0].status).toBe(503)
    await page.locator('.playground-response').screenshot({ path: path.join(dir, 'paused.png') })
    console.log('PASS: real creation handed to Main; exactly one injected 503; UI paused with original Job ID')

    await page.getByRole('button', { name: '继续查询', exact: true }).click()
    await expect.poll(async () => (await read()).snapshot.traceStatus, { timeout: 90000, intervals: [500, 1000] }).toBe('TERMINAL')
    const terminal = (await read()).snapshot
    expect(terminal.jobId).toBe(createdJobId)
    expect(terminal.latestJob.status).toBe('SUCCEEDED')
    const image = page.getByAltText('网页截图预览', { exact: true })
    await expect(image).toBeVisible({ timeout: 15000 })
    await expect.poll(() => image.evaluate(img => img.naturalWidth), { timeout: 10000 }).toBe(1280)
    const png = await page.evaluate(async () => {
      const modulePath = '/src/stores/screenshotTrackingStore.ts'
      const { useScreenshotTrackingStore } = await import(modulePath)
      const store = useScreenshotTrackingStore()
      if (!store.resultImage || store.resultJobId !== store.snapshot.jobId) throw Error('Missing current result')
      return [...new Uint8Array(await store.resultImage.arrayBuffer())]
    })
    const bytes = Buffer.from(png)
    expect(bytes.length).toBe(terminal.latestJob.resultSizeBytes)
    const sha256 = createHash('sha256').update(bytes).digest('hex')
    expect(sha256).toBe(terminal.latestJob.resultSha256)
    const requests = await instance.evaluate(() => globalThis.__resumeAcceptance.requests)
    expect(requests.every(r => r.jobId === createdJobId)).toBe(true)
    expect(requests.filter(r => r.injected)).toHaveLength(1)
    expect(requests.filter(r => r.kind === 'result')).toHaveLength(1)
    expect(postCount).toBe(1)
    expect(rendererStatusReads).toBe(0)
    await page.getByLabel('API Key', { exact: true }).fill('')
    await page.locator('.playground-response').screenshot({ path: path.join(dir, 'completed.png') })
    const updates = await page.evaluate(() => window.__resumeAcceptanceUpdates)
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({ realBackend: true, postCount, rendererStatusReads, jobId: createdJobId, injectedStatusFailures: 1, finalStatus: terminal.latestJob.status, pngBytes: bytes.length, pngMatchesBackendMetadata: true, requests, updates }, null, 2))
    console.log(`PASS: resumed same Job ID; one POST; terminal SUCCEEDED; automatic PNG ${bytes.length} bytes matches backend SHA-256`)
    console.log(`Evidence: ${dir}`)
    console.log('PASS: acceptance complete; the isolated window will close automatically after local cleanup.')
  } finally {
    if (page && !page.isClosed()) {
      await page.evaluate(async () => {
        window.__resumeAcceptanceDispose?.()
        await window.nexusDesktop?.stopScreenshotTracking()
        localStorage.clear(); sessionStorage.clear()
      }).catch(() => {})
    }
    try { await instance?.close() } finally { await vite?.close() }
    if (submitted) console.log('NOTE: one real backend task may exist; local stop does not cancel or delete it. No automatic resubmission.')
  }
})().catch(() => { console.error('Acceptance did not complete; inspect the isolated window or service state. No credentials printed.'); process.exitCode = 1 })
