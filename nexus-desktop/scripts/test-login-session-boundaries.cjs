const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')

;(async () => {
  const directory = await fs.mkdtemp(path.join(root, '.runtime/login-session-boundaries-'))
  let instance
  try {
    const env = { ...process.env, NEXUS_LOGIN_SESSION_TEST_DIR: directory,
      NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'),
      args: [path.join(__dirname, 'login-session-boundaries-main.cjs')], env, timeout: 20_000 })
    const page = await instance.firstWindow()
    const errors = []
    page.on('pageerror', error => errors.push(error.name))
    await page.waitForURL('nexus://app/login')
    await page.getByRole('button', { name: '登录', exact: true }).waitFor()
    const initial = await page.evaluate(async () => ({
      state: await window.nexusDesktop.readAuthSession(),
      legacyWriteAbsent: typeof window.nexusDesktop.writeAuthSession === 'undefined',
    }))
    assert.equal(initial.legacyWriteAbsent, true)
    assert.deepEqual(initial.state.authState, { revision: 0, session: null })
    const blocked = await page.evaluate(async () => {
      const results = []
      for (const url of ['/api/auth/login', '/api/auth/refresh', '/api/%61uth/login', '/api/auth/login/']) {
        const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
        const text = await response.text()
        results.push({ status: response.status, noRefreshToken: !text.includes('refreshToken') })
      }
      return results
    })
    assert.ok(blocked.every(result => result.status === 404 && result.noRefreshToken))
    assert.equal(await instance.evaluate(() => globalThis.__loginSessionBoundary.requests), 0)
    await page.getByPlaceholder('请输入邮箱').fill('controlled@example.test')
    await page.getByPlaceholder('请输入密码').fill('CONTROLLED_TEST_ONLY')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL('nexus://app/applications')
    await page.getByRole('heading', { name: 'Applications', exact: true }).waitFor()
    const inspect = () => page.evaluate(async () => {
      const main = await window.nexusDesktop.readAuthSession()
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      return {
        revision: main.authState.revision,
        expiresAt: main.authState.session?.expiresAt,
        tokenMatches: main.authState.session?.accessToken === store.accessToken,
        expiryMatches: main.authState.session?.expiresAt === store.expiresAt,
        sessionMatches: main.authState.session?.sessionId === store.sessionId &&
          main.authState.session?.sessionExpiresAt === store.sessionExpiresAt,
        noPrivateField: !JSON.stringify(main).includes('refreshToken') && !JSON.stringify(store.$state).includes('refreshToken'),
      }
    })
    const saved = await inspect()
    assert.equal(saved.revision, 1)
    assert.ok(saved.tokenMatches && saved.expiryMatches && saved.sessionMatches && saved.noPrivateField)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForURL('nexus://app/applications')
    await page.getByRole('heading', { name: 'Applications', exact: true }).waitFor()
    await expect.poll(async () => (await inspect()).expiryMatches).toBe(true)
    assert.deepEqual(await inspect(), saved)
    assert.equal(await instance.evaluate(() => globalThis.__loginSessionBoundary.requests), 3) // login plus two blocked Application reads
    // A second same-origin window still cannot invoke privileged auth operations.
    await instance.evaluate(async ({ BrowserWindow }, preload) => {
      const window = new BrowserWindow({ show: false, webPreferences: { preload, contextIsolation: true, nodeIntegration: false, sandbox: true } })
      globalThis.__untrustedAuthWindow = window
      await window.loadURL('nexus://app/login')
    }, path.join(root, 'preload.cjs'))
    const denied = await instance.evaluate(async () => globalThis.__untrustedAuthWindow.webContents.executeJavaScript(`window.nexusDesktop.loginAuthSession({ expectedRevision: 1, email: 'fixture@example.test', password: 'fixture-only' })`))
    assert.equal(denied.code, 'FORBIDDEN')
    for (const method of ['ensureAuthSession', 'logoutAuthSession', 'clearAuthSession', 'readAuthSession']) {
      const result = await instance.evaluate(async (_electron, method) =>
        globalThis.__untrustedAuthWindow.webContents.executeJavaScript(`window.nexusDesktop.${method}({ expectedRevision: 1 })`), method)
      assert.equal(result.code, 'FORBIDDEN')
    }
    await instance.evaluate(() => globalThis.__untrustedAuthWindow.destroy())
    const audit = await instance.evaluate(() => globalThis.__loginSessionBoundary)
    assert.equal(audit.blockedNetwork, 0)
    assert.deepEqual(errors, [])
    await fs.writeFile(path.join(directory, 'evidence.json'), JSON.stringify({ passed: true,
      backendMode: 'fixed mock; no real backend', legacyWriteAbsent: true,
      genericAuthRoutesBlocked: blocked.length, saved, refreshPreservedSnapshot: true,
      otherWindowDenied: true, audit, pageErrors: errors }, null, 2))
    console.log('PASS: real Main/Preload/login UI, private RT boundary, page reload, source guard and blocked generic auth routes')
  } finally {
    if (instance) await instance.close()
  }
})().catch(error => { console.error(error); process.exitCode = 1 })
