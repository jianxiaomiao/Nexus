const fs = require('node:fs/promises')
const path = require('node:path')
const { createRequire } = require('node:module')
const { pathToFileURL } = require('node:url')
const { deflateSync, crc32 } = require('node:zlib')
const root = path.resolve(__dirname, '..')
const consoleRoot = path.resolve(root, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const { _electron, expect } = consoleRequire('@playwright/test')

function makePng() {
  const chunk = (type, data) => {
    const name = Buffer.from(type), size = Buffer.alloc(4), crc = Buffer.alloc(4)
    size.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(Buffer.concat([name, data])))
    return Buffer.concat([size, name, data, crc])
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(1, 0); header.writeUInt32BE(1, 4); header[8] = 8; header[9] = 6
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(Buffer.from([0, 34, 124, 112, 255]))), chunk('IEND', Buffer.alloc(0))])
}

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'save-boundaries-'))
  let vite, instance
  try {
    // 使用当前桌面服务；未运行时创建只属于本测试的 Vite。
    let live = false
    try {
      const response = await fetch('http://127.0.0.1:5178/', { signal: AbortSignal.timeout(1500) })
      live = response.ok && (await response.text()).includes('/src/main.ts')
    } catch { /* 后面启动独立服务，端口冲突会明确失败。 */ }
    if (!live) {
      const { createServer } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
      vite = await createServer({ root: consoleRoot, configFile: path.join(consoleRoot, 'vite.config.ts'), cacheDir: path.join(root, '.runtime/vite-cache'), server: { host: '127.0.0.1', port: 5178, strictPort: true } })
      await vite.listen()
    }
    const env = { ...process.env, NEXUS_SAVE_BOUNDARY_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'save-boundaries-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    await page.waitForURL('**/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    const png = makePng(), bytes = [...png]
    await page.evaluate(data => { window.__boundaryPending = window.nexusDesktop.saveScreenshot(new Uint8Array(data).buffer) }, bytes)
    await expect.poll(() => instance.evaluate(() => globalThis.__saveBoundaryControl.calls)).toBe(1)
    const busy = await page.evaluate(data => window.nexusDesktop.saveScreenshot(new Uint8Array(data).buffer), bytes)
    expect(busy).toEqual({ status: 'failed', code: 'BUSY' })
    expect(await instance.evaluate(() => globalThis.__saveBoundaryControl.calls)).toBe(1)
    console.log('PASS: concurrent valid IPC returned BUSY; no second dialog')
    await instance.evaluate(() => { globalThis.__saveBoundaryControl.release() })
    expect(await page.evaluate(() => window.__boundaryPending)).toEqual({ status: 'cancelled' })
    const failure = await page.evaluate(data => window.nexusDesktop.saveScreenshot(new Uint8Array(data).buffer), bytes)
    expect(failure).toEqual({ status: 'failed', code: 'SAVE_FAILED' })
    expect(await instance.evaluate(() => globalThis.__saveBoundaryControl.calls)).toBe(2)
    expect((await fs.stat(dir)).isDirectory()).toBe(true)
    console.log('PASS: writing PNG to an existing directory caused real file-write failure')
    const recovered = await page.evaluate(data => window.nexusDesktop.saveScreenshot(new Uint8Array(data).buffer), bytes)
    expect(recovered).toEqual({ status: 'saved' })
    expect(await instance.evaluate(() => globalThis.__saveBoundaryControl.calls)).toBe(3)
    expect(await fs.readFile(path.join(dir, 'recovered.png'))).toEqual(png)
    console.log('PASS: save after write failure succeeded; file matches input byte-for-byte')
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({ busy, failure, recovered, bytes: png.length, dialogCalls: 3, byteEquality: true }, null, 2))
    console.log(`Evidence: ${dir}`)
  } finally {
    try { await instance?.close() } finally { await vite?.close() }
  }
})().catch(error => { console.error(error); process.exitCode = 1 })
