const fs = require('node:fs/promises')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const root = path.resolve(__dirname, '..')
const consoleRoot = path.resolve(root, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const { _electron, expect } = consoleRequire('@playwright/test')
const key = `nxk_v1_00000000-0000-4000-8000-000000000000_${'A'.repeat(43)}`
const id = n => `30000000-0000-4000-8000-${String(n).padStart(12, '0')}`

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'notification-boundaries-'))
  let vite, instance
  const evidence = []
  try {
    let live = false
    try { const r = await fetch('http://127.0.0.1:5178/', { signal: AbortSignal.timeout(1500) }); live = r.ok && (await r.text()).includes('/src/main.ts') } catch {}
    if (!live) {
      const { createServer } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
      vite = await createServer({ root: consoleRoot, configFile: path.join(consoleRoot, 'vite.config.ts'), cacheDir: path.join(root, '.runtime/vite-cache'), server: { host: '127.0.0.1', port: 5178, strictPort: true, open: false } })
      await vite.listen()
    }
    const env = { ...process.env, NEXUS_TRACKING_TEST_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'notification-boundaries-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    await page.waitForURL('**/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    const stats = () => instance.evaluate(() => {
      const c = globalThis.__notificationTest
      return { supportChecks: c.supportChecks, attempts: c.attempts, shown: c.shown, failed: c.failed, nativeErrors: c.nativeErrors, unhandled: c.unhandled }
    })
    const stop = () => page.evaluate(() => window.nexusDesktop.stopScreenshotTracking())
    const start = jobId => page.evaluate(input => window.nexusDesktop.startScreenshotTracking(input), { jobId, apiKey: key })
    const read = () => page.evaluate(() => window.nexusDesktop.readScreenshotTracking())
    async function settle(jobId, status) {
      const previous = await instance.evaluate(() => globalThis.__trackingTest.requests.length)
      await start(jobId)
      await expect.poll(() => instance.evaluate(() => globalThis.__trackingTest.requests.length)).toBe(previous + 1)
      await instance.evaluate((_electron, { previous, status }) => { globalThis.__trackingTest.requests[previous].release(status) }, { previous, status })
      await expect.poll(async () => (await read()).snapshot.latestJob?.status).toBe(status)
    }
    async function terminal(jobId, status) {
      const snapshot = (await read()).snapshot
      expect(snapshot.jobId).toBe(jobId)
      expect(snapshot.traceStatus).toBe('TERMINAL')
      expect(snapshot.latestJob.status).toBe(status)
      expect(snapshot.queryFailedInfo).toBe(null)
      expect((await stats()).unhandled).toBe(0)
    }

    await settle(id(1), 'SUCCEEDED'); await terminal(id(1), 'SUCCEEDED')
    expect((await stats()).attempts).toHaveLength(1)
    await start(id(1))
    expect((await stats()).attempts).toHaveLength(1)
    await stop(); await settle(id(1), 'SUCCEEDED')
    expect((await stats()).attempts).toHaveLength(1)
    await terminal(id(1), 'SUCCEEDED')
    evidence.push('same Job ID not retried, including stop and re-track')
    console.log('PASS: Job ID dedup survives local stop and re-track')

    await stop(); await settle(id(2), 'FAILED'); await terminal(id(2), 'FAILED')
    expect((await stats()).attempts.at(-1).body).toContain('失败')
    const beforeExpired = (await stats()).supportChecks
    await stop(); await settle(id(3), 'EXPIRED'); await terminal(id(3), 'EXPIRED')
    expect((await stats()).supportChecks).toBe(beforeExpired)
    evidence.push('FAILED notifies; EXPIRED does not')

    let index = 4
    for (const mode of ['support-throw', 'constructor-throw', 'show-throw', 'async-failed', 'unsupported']) {
      await stop()
      await instance.evaluate((_electron, mode) => { globalThis.__notificationTest.mode = mode }, mode)
      const jobId = id(index++)
      const before = await stats()
      await settle(jobId, 'SUCCEEDED')
      if (mode === 'async-failed') await expect.poll(async () => (await stats()).failed).toBe(before.failed + 1)
      await terminal(jobId, 'SUCCEEDED')
      const after = await stats()
      expect(after.supportChecks).toBe(before.supportChecks + 1)
      await stop(); await settle(jobId, 'SUCCEEDED')
      expect((await stats()).supportChecks).toBe(after.supportChecks)
      expect((await stats()).attempts.length).toBe(after.attempts.length)
      await terminal(jobId, 'SUCCEEDED')
      evidence.push({ mode, terminalUnchanged: true, duplicateRetry: false, unhandled: 0 })
      console.log(`PASS: ${mode}; state remains TERMINAL/SUCCEEDED; no retry or unhandled rejection`)
    }
    await stop()
    await instance.evaluate(() => { globalThis.__notificationTest.mode = 'mock' })
    const checksBeforeRunning = (await stats()).supportChecks
    await settle(id(9), 'RUNNING')
    expect((await stats()).supportChecks).toBe(checksBeforeRunning)
    await stop()
    console.log('PASS: RUNNING and EXPIRED do not notify')
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({ evidence, stats: await stats() }, null, 2))
    console.log(`Evidence: ${dir}`)

    if (process.argv.includes('--native')) {
      await instance.evaluate(() => { globalThis.__notificationTest.mode = 'native' })
      const beforeNative = await stats()
      console.log('NATIVE: sending one real Windows notification; simulated successful job, no backend request. Window stays open for 45 seconds.')
      await settle(id(10), 'SUCCEEDED'); await terminal(id(10), 'SUCCEEDED')
      await expect.poll(async () => {
        const s = await stats()
        return s.shown > beforeNative.shown || s.failed > beforeNative.failed
      }, { timeout: 15000 }).toBe(true)
      const native = await stats()
      await fs.writeFile(path.join(dir, 'native.json'), JSON.stringify({ shown: native.shown, failedEvents: native.failed - beforeNative.failed, nativeErrors: native.nativeErrors, terminalUnchanged: true, userVisible: 'requires manual confirmation' }, null, 2))
      console.log(`NATIVE: shown=${native.shown}; errors=${JSON.stringify(native.nativeErrors)}`)
      await page.waitForTimeout(45000)
      console.log('NATIVE: observation period complete; closing isolated test window.')
    }
  } finally {
    if (instance) {
      await instance.evaluate(() => { for (const notification of globalThis.__notificationTest.nativeInstances) notification.close() }).catch(() => {})
    }
    try { await instance?.close() } finally { await vite?.close() }
  }
})().catch(error => { console.error(error.message); process.exitCode = 1 })
