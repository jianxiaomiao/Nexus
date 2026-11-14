const fs = require('node:fs/promises')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const { createHash } = require('node:crypto')
const root = path.resolve(__dirname, '..')
const consoleRoot = path.resolve(root, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const { _electron, expect } = consoleRequire('@playwright/test')
const notificationAcceptance = process.argv.includes('--notify')
const handoffAcceptance = process.argv.includes('--handoff')
const observeNotifications = notificationAcceptance || handoffAcceptance

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const prefix = handoffAcceptance ? 'handoff-acceptance-' : notificationAcceptance ? 'screenshot-notification-acceptance-' : 'resume-acceptance-'
  const dir = await fs.mkdtemp(path.join(root, '.runtime', prefix))
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
    const env = { ...process.env, NEXUS_RESUME_ACCEPTANCE_DIR: dir, NEXUS_RESUME_ACCEPTANCE_NOTIFY: notificationAcceptance ? '1' : '0', NEXUS_RESUME_ACCEPTANCE_HANDOFF: handoffAcceptance ? '1' : '0', NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'resume-acceptance-main.cjs')], env, timeout: 20000 })
    page = await instance.firstWindow()
    await page.waitForURL('**/login')
    console.log('WAITING: log in inside the isolated Nexus window; do not send credentials in chat.')
    await page.waitForURL('**/applications', { timeout: 15 * 60 * 1000 })
    await page.getByRole('link', { name: 'API 调试台' }).click()
    await page.getByRole('button', { name: '网页截图', exact: true }).click()
    await page.evaluate(() => {
      window.__acceptanceReady = false
      const panel = document.createElement('aside')
      panel.id = 'nexus-acceptance-ready'
      panel.setAttribute('aria-label', '验收准备')
      Object.assign(panel.style, { position: 'fixed', bottom: '20px', left: '20px', zIndex: '2147483647', width: '360px', padding: '18px', background: '#fff', color: '#173c35', border: '2px solid #227c70', borderRadius: '12px', boxShadow: '0 4px 24px #0003', fontSize: '14px' })
      const text = document.createElement('p')
      text.textContent = '请先填写截图网址和 API Key，再手动关闭 VPN。只有点击下方按钮后，脚本才会提交一次真实截图。请勿点击页面原有的创建按钮。'
      const button = document.createElement('button')
      button.type = 'button'
      button.textContent = 'VPN 已关闭，开始验收'
      Object.assign(button.style, { padding: '10px', background: '#227c70', color: 'white', border: '0', borderRadius: '6px', cursor: 'pointer' })
      button.addEventListener('click', () => {
        window.__acceptanceReady = true
        button.disabled = true
        text.textContent = '准备已确认。脚本将在输入有效后开始，请保持 VPN 关闭，等待结果。'
      }, { once: true })
      panel.append(text, button)
      document.body.append(panel)
    })
    console.log('WAITING: fill API Key and URL, turn VPN off yourself, then click the green acceptance button. No task is created before this explicit signal.')
    await expect.poll(() => page.evaluate(() => {
      const key = document.querySelector('#playground-key')
      const url = document.querySelector('#playground-screenshot-url')
      try {
        const target = new URL(url?.value?.trim())
        return window.__acceptanceReady === true && /^nxk_v1_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[A-Za-z0-9_-]{43}$/.test(key?.value?.trim() ?? '') && target.protocol === 'https:' &&
          ['blog.csdn.net', 'zhuanlan.zhihu.com'].includes(target.hostname) &&
          !target.username && !target.password && !target.port && !target.hash && target.pathname.length > 1
      } catch { return false }
    }), { timeout: 15 * 60 * 1000, intervals: [1000] }).toBe(true)
    await page.evaluate(() => document.getElementById('nexus-acceptance-ready')?.remove())

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
    if (handoffAcceptance) {
      await expect.poll(() => page.evaluate(async () => {
        const modulePath = '/src/stores/screenshotTrackingStore.ts'
        const { useScreenshotTrackingStore } = await import(modulePath)
        const store = useScreenshotTrackingStore()
        return { submitting: store.submitting, pendingId: store.pendingHandoffJob?.jobId, retries: store.handoffRetryCount }
      })).toEqual({ submitting: false, pendingId: createdJobId, retries: 0 })
      const beforeRetry = await instance.evaluate(() => ({ calls: globalThis.__resumeAcceptance.handoffCalls, requests: globalThis.__resumeAcceptance.requests.length }))
      expect(beforeRetry.calls).toEqual([{ jobId: createdJobId, injected: true }])
      expect(beforeRetry.requests).toBe(0)
      await expect(page.getByRole('button', { name: '创建异步任务', exact: true })).toBeDisabled()
      await page.locator('.playground-response').screenshot({ path: path.join(dir, 'pending-handoff.png') })
      console.log('PASS: one real job created; first handoff alone injected BUSY; Job ID retained, no status query yet')
      await page.getByRole('button', { name: '重试接手', exact: true }).click()
      await expect.poll(() => page.evaluate(async () => {
        const modulePath = '/src/stores/screenshotTrackingStore.ts'
        const { useScreenshotTrackingStore } = await import(modulePath)
        const store = useScreenshotTrackingStore()
        return store.pendingHandoffJob === null && !store.retryingHandoff && store.handoffRetryCount === 0
      })).toBe(true)
      console.log('PASS: page retried start only; original Job ID accepted; pending record cleared')
    } else if (!notificationAcceptance) {
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
    }
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
    expect(requests.filter(r => r.injected)).toHaveLength(observeNotifications ? 0 : 1)
    expect(requests.filter(r => r.kind === 'result')).toHaveLength(1)
    expect(postCount).toBe(1)
    expect(rendererStatusReads).toBe(0)
    await page.getByLabel('API Key', { exact: true }).fill('')
    await page.locator('.playground-response').screenshot({ path: path.join(dir, 'completed.png') })
    const updates = await page.evaluate(() => window.__resumeAcceptanceUpdates)
    let notification = null
    if (observeNotifications) {
      await expect.poll(() => instance.evaluate(() => {
        const c = globalThis.__resumeAcceptance
        return c.notificationShown + c.notificationErrors.length
      }), { timeout: 15000 }).toBeGreaterThan(0)
      notification = await instance.evaluate(() => {
        const c = globalThis.__resumeAcceptance
        return { attempts: c.notificationAttempts, shown: c.notificationShown, errors: c.notificationErrors, userVisible: 'requires manual confirmation' }
      })
      expect(notification.attempts).toBe(1)
      expect(notification.shown).toBe(1)
      expect(notification.errors).toHaveLength(0)
    }
    const handoffCalls = await instance.evaluate(() => globalThis.__resumeAcceptance.handoffCalls)
    if (handoffAcceptance) {
      expect(handoffCalls).toEqual([{ jobId: createdJobId, injected: true }, { jobId: createdJobId, injected: false }])
    }
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({ realBackend: true, userConfirmedVpnReady: true, postCount, rendererStatusReads, jobId: createdJobId, injectedStatusFailures: observeNotifications ? 0 : 1, handoffCalls, finalStatus: terminal.latestJob.status, pngBytes: bytes.length, pngMatchesBackendMetadata: true, requests, updates, notification }, null, 2))
    console.log(`PASS: one POST; same Job ID; terminal SUCCEEDED; automatic PNG ${bytes.length} bytes matches backend SHA-256`)
    console.log(`Evidence: ${dir}`)
    if (observeNotifications) {
      console.log('NATIVE: real screenshot completion notification shown=1, failed=0; window stays open for 45 seconds. Check the Windows banner/notification center.')
      await page.waitForTimeout(45000)
    }
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
