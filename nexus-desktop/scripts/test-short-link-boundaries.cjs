const fs = require('node:fs/promises')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')

async function checkSourceGuard() {
  const source = await fs.readFile(path.join(root, 'main.cjs'), 'utf8')
  const context = vm.createContext({ URL, console })
  const start = source.indexOf('function isTrustedScreenshotCaller(')
  vm.runInContext(source.slice(start, source.indexOf('// 当前仅实现开发模式', start)), context)
  const frame = { url: 'nexus://app/short-links' }
  const contents = { mainFrame: frame }
  const owner = { isDestroyed: () => false, webContents: contents }
  const event = { sender: contents, senderFrame: frame }
  context.mainWindow = owner
  assert.equal(context.isTrustedScreenshotCaller(event), true)
  assert.equal(context.isTrustedScreenshotCaller({ ...event, sender: {} }), false)
  assert.equal(context.isTrustedScreenshotCaller({ ...event, senderFrame: { url: frame.url } }), false)
  assert.equal(context.isTrustedScreenshotCaller({ ...event, senderFrame: null }), false)
  for (const url of ['nexus://evil/short-links', 'nexus://app:123/short-links', 'nexus://user@app/short-links', 'http://127.0.0.1:5178/', 'invalid']) {
    frame.url = url
    assert.equal(context.isTrustedScreenshotCaller(event), false)
  }
  frame.url = 'nexus://app/short-links'
  owner.isDestroyed = () => true
  assert.equal(context.isTrustedScreenshotCaller(event), false)
  context.mainWindow = null
  assert.equal(context.isTrustedScreenshotCaller(event), false)
  context.mainWindow = { isDestroyed: () => false, webContents: contents }
  assert.equal(context.isTrustedScreenshotCaller({ sender: contents, get senderFrame() { throw Error('Disposed frame') } }), false)
  console.log('PASS: source guard rejects wrong window, subframe, URL, missing/destroyed window and disposed frame (isolated guard checks)')
}

;(async () => {
  await checkSourceGuard()
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'short-link-boundaries-'))
  let instance
  try {
    const env = { ...process.env, NEXUS_SHORT_LINK_BOUNDARY_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'short-link-boundaries-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    await page.waitForURL('nexus://app/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    const call = value => page.evaluate(input => window.nexusDesktop.openShortLink(input), value)
    const invalid = [null, undefined, 123, true, [], {}, { shortCode: 'Ab12Cd34Ef56' }, '', 'Ab12Cd34Ef5', 'Ab12Cd34Ef567', 'Ab12Cd34Ef56\n', 'Ab12Cd34Ef56\r\n', ' Ab12Cd34Ef56', 'Ab12Cd34Ef56 ', 'Ab12Cd34Ef5_', 'Ab12Cd34Ef5/', 'Ab12Cd34Ef5?', 'Ab12Cd34Ef5#', 'Ａb12Cd34Ef56', '/s/Ab12Cd34Ef56', 'https://evil.invalid', 'file:///C:/test', 'javascript:alert(1)']
    for (const value of invalid) assert.deepEqual(await call(value), { status: 'failed', code: 'INVALID_REQUEST' })
    assert.equal(await instance.evaluate(() => globalThis.__shortLinkBoundary.calls.length), 0)
    console.log(`PASS: ${invalid.length} invalid inputs rejected through real Preload/IPC without opening anything`)
    const shortCode = 'Ab12Cd34Ef56'
    assert.deepEqual(await call(shortCode), { status: 'opened' })
    assert.deepEqual(await instance.evaluate(() => globalThis.__shortLinkBoundary.calls), [`http://localhost:8080/s/${shortCode}`])
    console.log('PASS: trusted main frame opens only the Main-built fixed HTTP short address')
    for (const mode of ['throw', 'reject']) {
      await instance.evaluate((_electron, value) => { globalThis.__shortLinkBoundary.mode = value }, mode)
      assert.deepEqual(await call(shortCode), { status: 'failed', code: 'OPEN_FAILED' })
    }
    await instance.evaluate(() => { globalThis.__shortLinkBoundary.mode = 'success' })
    assert.deepEqual(await call(shortCode), { status: 'opened' })
    console.log('PASS: synchronous failure and Promise rejection translated; subsequent call recovers')
    await instance.evaluate(() => { globalThis.__shortLinkBoundary.mode = 'pending' })
    await page.evaluate(code => {
      window.__openSettled = false
      window.__openPending = window.nexusDesktop.openShortLink(code).then(result => { window.__openSettled = true; return result })
    }, shortCode)
    await expect.poll(() => instance.evaluate(() => typeof globalThis.__shortLinkBoundary.release)).toBe('function')
    assert.equal(await page.evaluate(() => window.__openSettled), false)
    await instance.evaluate(() => globalThis.__shortLinkBoundary.release())
    assert.deepEqual(await page.evaluate(() => window.__openPending), { status: 'opened' })
    console.log('PASS: opened is returned only after the system Promise resolves')
    const otherWindowPromise = instance.waitForEvent('window')
    await instance.evaluate(async ({ BrowserWindow }, preload) => {
      const other = new BrowserWindow({ show: false, webPreferences: { preload, sandbox: true, contextIsolation: true, nodeIntegration: false } })
      await other.loadURL('nexus://app/login')
    }, path.join(root, 'preload.cjs'))
    const otherPage = await otherWindowPromise
    const before = await instance.evaluate(() => globalThis.__shortLinkBoundary.calls.length)
    assert.deepEqual(await otherPage.evaluate(code => window.nexusDesktop.openShortLink(code), shortCode), { status: 'failed', code: 'FORBIDDEN' })
    assert.equal(await instance.evaluate(() => globalThis.__shortLinkBoundary.calls.length), before)
    await otherPage.close()
    console.log('PASS: second window with the same trusted URL rejected through real IPC')
    const result = await page.evaluate(async () => {
      const response = await fetch('nexus://app/s/Ab12Cd34Ef56')
      return { status: response.status, body: await response.text() }
    })
    assert.equal(result.status, 404)
    assert.equal(await instance.evaluate(() => globalThis.__shortLinkBoundary.calls.length), before)
    const evidence = await instance.evaluate(() => ({ calls: globalThis.__shortLinkBoundary.calls, networkCalls: globalThis.__shortLinkBoundary.networkCalls, unhandled: globalThis.__shortLinkBoundary.unhandled }))
    assert.equal(evidence.networkCalls, 0); assert.equal(evidence.unhandled, 0)
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({ passed: true, invalidInputs: invalid.length, ...evidence }, null, 2))
    console.log('PASS: protocol /s/ does not open browser; no backend calls or unhandled rejections')
    console.log(`Evidence: ${dir}`)
  } finally { await instance?.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
