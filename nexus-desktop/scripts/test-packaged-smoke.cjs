const fs = require('node:fs/promises')
const path = require('node:path')
const assert = require('node:assert/strict')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')

;(async () => {
  const executables = {
    darwin: ['mac', 'Nexus Desktop.app', 'Contents', 'MacOS', 'Nexus Desktop'],
    win32: ['win-unpacked', 'Nexus Desktop.exe'],
  }
  const parts = executables[process.platform]
  if (!parts) throw new Error(`Unsupported packaged smoke platform: ${process.platform}`)
  const executablePath = process.argv[2] ? path.resolve(process.argv[2]) : path.join(root, 'release', ...parts)
  await fs.access(executablePath)
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'packaged-smoke-'))
  const profile = path.join(dir, 'profile')
  const env = { ...process.env, NEXUS_DESKTOP_SMOKE: '1', NEXUS_DESKTOP_DIAGNOSE: '1' }
  delete env.ELECTRON_RUN_AS_NODE
  let instance
  try {
    // 使用实际打包入口，只有Chromium用户目录通过正式命令行参数隔离。
    instance = await _electron.launch({ executablePath, args: [`--user-data-dir=${profile}`], cwd: dir, env, timeout: 20000 })
    const runtime = await instance.evaluate(({ app }) => ({ packaged: app.isPackaged, userData: app.getPath('userData'), appPath: app.getAppPath(), name: app.getName() }))
    assert.equal(runtime.packaged, true)
    assert.equal(path.resolve(runtime.userData).toLowerCase(), path.resolve(profile).toLowerCase())
    assert.ok(runtime.appPath.endsWith('app.asar'))
    const page = await instance.firstWindow()
    await page.waitForURL('nexus://app/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
    await expect.poll(() => instance.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isVisible()), { timeout: 10000 }).toBe(true)
    let httpRequests = 0
    page.on('request', request => { if (/^https?:/.test(request.url())) httpRequests++ })
    await page.reload()
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    assert.equal(httpRequests, 0)
    const renderer = await page.evaluate(async () => ({
      noNode: typeof window.require === 'undefined' && typeof window.process === 'undefined',
      shortLinkBridge: typeof window.nexusDesktop?.openShortLink === 'function',
      ipc: await window.nexusDesktop.readScreenshotTracking(),
    }))
    assert.equal(renderer.noNode, true)
    assert.equal(renderer.shortLinkBridge, true)
    assert.equal(renderer.ipc.status, 'ok')
    await instance.close()
    instance = null
    const state = JSON.parse(await fs.readFile(path.join(profile, 'window-state.json'), 'utf8'))
    assert.equal(state.version, 1)
    assert.equal(typeof state.maximized, 'boolean')
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify({ passed: true, runtime, renderer, historyReload: true, httpRequests, windowStateWritten: true, developerFlagsIgnored: true }, null, 2))
    console.log('PASS: real packaged executable, ASAR page/Preload/IPC, History reload, isolated writable userData and window state; developer flags ignored')
    console.log(`Evidence: ${dir}`)
  } finally { await instance?.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
