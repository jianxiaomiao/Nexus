const fs = require('node:fs/promises')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const root = path.resolve(__dirname, '..')
const consoleRoot = path.resolve(root, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const { _electron, expect } = consoleRequire('@playwright/test')
const key = `nxk_v1_00000000-0000-4000-8000-000000000000_${'A'.repeat(43)}` // 仅格式合法的模拟凭据。
const jobA = '10000000-0000-4000-8000-000000000001'
const jobB = '20000000-0000-4000-8000-000000000002'
;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'tracking-boundaries-'))
  const evidence = []
  let vite, instance
  try {
    let live = false
    try { const r = await fetch('http://127.0.0.1:5178/', { signal: AbortSignal.timeout(1500) }); live = r.ok && (await r.text()).includes('/src/main.ts') } catch {}
    if (!live) {
      const { createServer } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
      vite = await createServer({ root: consoleRoot, configFile: path.join(consoleRoot, 'vite.config.ts'), cacheDir: path.join(root, '.runtime/vite-cache'), server: { host: '127.0.0.1', port: 5178, strictPort: true } })
      await vite.listen()
    }
    const env = { ...process.env, NEXUS_TRACKING_TEST_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }; delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'tracking-boundaries-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    await page.waitForURL('**/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    await page.evaluate(() => { window.__trackingUpdates = []; window.nexusDesktop.onScreenshotTrackingUpdated(s => window.__trackingUpdates.push(s)) })
    const start = id => page.evaluate(input => window.nexusDesktop.startScreenshotTracking(input), { jobId: id, apiKey: key })
    const read = () => page.evaluate(() => window.nexusDesktop.readScreenshotTracking())
    const stop = () => page.evaluate(() => window.nexusDesktop.stopScreenshotTracking())
    const count = () => instance.evaluate(() => globalThis.__trackingTest.requests.length)
    const release = (index, status) => instance.evaluate((_electron, { index, status }) => { globalThis.__trackingTest.requests[index].release(status) }, { index, status })
    const phase = async (status, id = jobA) => expect.poll(async () => { const r = await read(); return [r.snapshot.traceStatus, r.snapshot.jobId] }).toEqual([status, id])

    expect((await start(jobA)).status).toBe('ok')
    await expect.poll(count).toBe(1)
    expect((await start(jobA)).status).toBe('ok')
    expect(await start(jobB)).toEqual({ status: 'failed', code: 'BUSY' })
    await page.waitForTimeout(2300) // 跨过完整轮询间隔，响应仍未完成。
    expect(await count()).toBe(1)
    await release(0, 'RUNNING')
    await expect.poll(count, { timeout: 5000 }).toBe(2)
    await release(1, 'SUCCEEDED')
    await phase('TERMINAL')
    expect((await start(jobA)).snapshot.traceStatus).toBe('TERMINAL')
    await page.waitForTimeout(2300)
    expect(await count()).toBe(2)
    evidence.push('same-job dedup; different-job BUSY; serial polling; SUCCEEDED stops')
    console.log('PASS: deduplication, serial polling and terminal no-requery')

    await stop()
    await instance.evaluate(() => { globalThis.__trackingTest.ignoreAbortNext = true })
    await start(jobA); await expect.poll(count).toBe(3)
    await stop()
    expect((await read()).snapshot.traceStatus).toBe('IDLE')
    expect(await instance.evaluate(() => globalThis.__trackingTest.requests[2].aborted)).toBe(true)
    await start(jobB); await expect.poll(count).toBe(4)
    const eventsBefore = await page.evaluate(() => window.__trackingUpdates.length)
    await release(2, 'SUCCEEDED') // 即使请求不遵守 abort，旧响应也不应生效。
    await page.waitForTimeout(250)
    await phase('POLLING', jobB)
    expect((await read()).snapshot.latestJob).toBe(null)
    expect(await page.evaluate(() => window.__trackingUpdates.length)).toBe(eventsBefore)
    await page.waitForTimeout(2300)
    expect(await count()).toBe(4)
    await release(3, 'FAILED'); await phase('TERMINAL', jobB)
    await page.waitForTimeout(2300); expect(await count()).toBe(4)
    evidence.push('stop abort; stale A cannot overwrite B, publish or schedule; FAILED stops')
    console.log('PASS: late response after stop cannot overwrite current task or schedule polling')

    await stop(); await start(jobA); await expect.poll(count).toBe(5)
    await release(4, 'RUNNING'); await expect.poll(count, { timeout: 5000 }).toBe(6)
    const began = Date.now()
    await phaseWithTimeout()
    async function phaseWithTimeout() { await expect.poll(async () => (await read()).snapshot.traceStatus, { timeout: 12000 }).toBe('PAUSED') }
    const snapshot = (await read()).snapshot
    expect(snapshot.latestJob.status).toBe('RUNNING')
    expect(snapshot.queryFailedInfo).toContain('超时')
    expect(await instance.evaluate(() => globalThis.__trackingTest.requests[5].aborted)).toBe(true)
    await page.waitForTimeout(2300); expect(await count()).toBe(6)
    evidence.push({ timeoutPaused: true, waitMs: Date.now() - began, retainedLatestJob: 'RUNNING' })
    console.log('PASS: actual 8-second deadline pauses tracking and preserves last task state')

    await start(jobA); await expect.poll(count).toBe(7)
    await release(6, 'EXPIRED'); await phase('TERMINAL')
    await page.waitForTimeout(2300); expect(await count()).toBe(7)
    await stop()
    const all = await page.evaluate(() => window.__trackingUpdates)
    expect(JSON.stringify(all)).not.toContain(key)
    expect(await instance.evaluate(() => globalThis.__trackingTest.requests.every(r => r.redirectBlocked))).toBe(true)
    evidence.push('resume same paused job; EXPIRED stops; snapshots contain no fake key')
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(`PASS: all tracking checks; Evidence: ${dir}`)
  } finally {
    try { await instance?.close() } finally { await vite?.close() }
  }
})().catch(error => { console.error(error); process.exitCode = 1 })
