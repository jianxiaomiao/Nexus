const fs = require('node:fs/promises')
const path = require('node:path')
const assert = require('node:assert/strict')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'login-acceptance-'))
  let instance
  const evidence = { passed: false, pageHttpRequests: 0 }
  try {
    const env = { ...process.env, NEXUS_LOGIN_ACCEPTANCE_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'login-acceptance-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    page.on('request', request => { if (/^https?:/.test(request.url())) evidence.pageHttpRequests++ })
    await page.waitForURL('nexus://app/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    evidence.boundaries = await page.evaluate(async () => {
      const badLogin = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      const badData = await badLogin.json()
      const anonymous = await fetch('/api/application')
      const anonData = await anonymous.json()
      const ipc = await window.nexusDesktop.readScreenshotTracking()
      return { badLogin: badLogin.status, badLoginCode: badData.code, anonymous: anonymous.status, anonymousCode: anonData.code, ipc: ipc.status }
    })
    assert.equal(evidence.boundaries.badLogin, 400)
    assert.equal(evidence.boundaries.anonymous, 401)
    assert.equal(evidence.boundaries.ipc, 'ok')
    console.log('PASS: real backend validation 400, anonymous management 401, real IPC trusted source')
    console.log('WAITING: log in inside the isolated Nexus window. Do not send credentials in chat. Successful acceptance closes the window after 30 seconds.')
    await page.waitForURL('nexus://app/applications', { timeout: 15 * 60 * 1000 })
    await expect.poll(async () => instance.evaluate(() => globalThis.__loginAcceptance.requests.some(record => record.path === '/api/application' && record.status === 200 && record.hasBearer)), { timeout: 20000 }).toBe(true)
    evidence.requests = await instance.evaluate(() => globalThis.__loginAcceptance.requests)
    assert.ok(evidence.requests.some(record => record.path === '/api/auth/login' && record.status === 200 && !record.hasBearer))
    assert.equal(evidence.pageHttpRequests, 0)
    assert.equal(await instance.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isVisible()), true)
    evidence.passed = true
    console.log('PASS: real UI login 200, Bearer management list 200, visible nexus page, no Renderer HTTP/HTTPS requests')
    await page.evaluate(() => {
      const banner = document.createElement('div')
      banner.textContent = '独立登录验收通过：登录及 Application 列表成功。此隔离测试窗口将在 30 秒后自动关闭。'
      Object.assign(banner.style, { position: 'fixed', bottom: '20px', left: '20px', right: '20px', padding: '20px', background: '#173c35', color: 'white', zIndex: '2147483647', borderRadius: '12px' })
      document.body.append(banner)
    })
    await new Promise(resolve => setTimeout(resolve, 30000))
  } catch {
    evidence.failed = true
    console.error('FAIL: login acceptance incomplete; inspect status-only evidence and the window/backend state.')
    process.exitCode = 1
  } finally {
    if (instance) {
      try { evidence.requests = await instance.evaluate(() => globalThis.__loginAcceptance.requests) } catch {}
      await instance.close().catch(() => {})
    }
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(`Evidence: ${dir}`)
  }
})().catch(() => { console.error('Login acceptance setup failed'); process.exitCode = 1 })
