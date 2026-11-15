const fs = require('node:fs/promises')
const path = require('node:path')
const assert = require('node:assert/strict')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')
const logoutMode = process.argv.includes('--logout')

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', logoutMode ? 'session-logout-live-' : 'session-refresh-live-'))
  const evidence = { passed: false, mode: logoutMode ? 'logout' : 'refresh-and-restart', phase: 'setup', pageHttpRequests: 0, runs: [], confirmations: [] }
  let instance, page
  const save = () => fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify(evidence, null, 2))
  async function collect() {
    return instance.evaluate(() => globalThis.__sessionAcceptance)
  }
  async function launch() {
    const env = { ...process.env, NEXUS_SESSION_ACCEPTANCE_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({
      executablePath: require('electron'),
      args: [path.join(__dirname, 'session-refresh-acceptance-main.cjs')], env, timeout: 25000,
    })
    page = await instance.firstWindow()
    page.on('request', request => { if (/^https?:/.test(request.url())) evidence.pageHttpRequests++ })
    await page.waitForURL('nexus://app/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    const runtime = await instance.evaluate(({ app }) => ({ userData: app.getPath('userData'), packaged: app.isPackaged }))
    assert.equal(runtime.userData, path.join(dir, 'profile'))
    assert.equal(runtime.packaged, false)
    await expect.poll(() => instance.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isVisible()), { timeout: 20000 }).toBe(true)
    return { pid: instance.process().pid, ...runtime }
  }
  async function snapshot() {
    // Compare credentials inside the Renderer; only booleans and metadata leave it.
    return page.evaluate(async () => {
      const app = document.querySelector('#app')?.__vue_app__
      const store = app?.config.globalProperties.$pinia?._s.get('userStore')
      if (!store) throw Error('Mounted user Store unavailable')
      const result = await window.nexusDesktop.readAuthSession()
      if (result.status !== 'ok') throw Error('Trusted session read rejected')
      const main = result.authState
      return {
        path: location.pathname, initializationStatus: store.initializationStatus,
        storeRevision: store.mainRevision, mainRevision: main.revision,
        storeHasToken: Boolean(store.accessToken), mainHasSession: main.session !== null,
        storeExpiresAt: store.expiresAt, mainExpiresAt: main.session?.expiresAt ?? null,
        clearing: store.clearing, hasClearError: Boolean(store.clearError),
        tokensMatch: Boolean(store.accessToken) && store.accessToken === main.session?.accessToken,
        noNode: typeof window.require === 'undefined' && typeof window.process === 'undefined',
      }
    })
  }
  function loggedIn(state) {
    assert.equal(state.path, '/applications')
    assert.equal(state.initializationStatus, 'ready')
    assert.equal(state.storeHasToken, true)
    assert.equal(state.mainHasSession, true)
    assert.equal(state.tokensMatch, true)
    assert.equal(state.storeRevision, state.mainRevision)
    assert.equal(state.storeExpiresAt, state.mainExpiresAt)
    assert.ok(state.mainExpiresAt > Date.now())
    assert.equal(state.noNode, true)
  }
  function loggedOut(state, revision) {
    assert.equal(state.path, '/login')
    assert.equal(state.initializationStatus, 'ready')
    assert.equal(state.storeHasToken, false)
    assert.equal(state.mainHasSession, false)
    assert.equal(state.storeRevision, revision)
    assert.equal(state.mainRevision, revision)
    assert.equal(state.storeExpiresAt, 0)
    assert.equal(state.mainExpiresAt, null)
    assert.equal(state.clearing, false)
    assert.equal(state.hasClearError, false)
    assert.equal(state.noNode, true)
  }
  function auditRequests() {
    const requests = evidence.runs.flatMap(run => run.requests)
    evidence.requestAudit = {
      loginRequests: requests.filter(r => r.path === '/api/auth/login').length,
      successfulApplicationListResponses: requests.filter(r => r.path === '/api/application' && r.status === 200).length,
      blockedOutOfScopeRequests: requests.filter(r => !r.allowed).map(r => ({ method: r.method })),
      onlyLoginPostsForwarded: requests.filter(r => r.allowed && r.method === 'POST').every(r => r.path === '/api/auth/login'),
    }
    assert.equal(evidence.requestAudit.onlyLoginPostsForwarded, true)
  }
  async function confirm(message, buttonText) {
    await page.evaluate(({ message, buttonText }) => {
      document.querySelector('#session-acceptance-panel')?.remove()
      window.__sessionAcceptanceConfirmed = false
      const panel = document.createElement('section')
      panel.id = 'session-acceptance-panel'
      panel.setAttribute('aria-label', '会话验收控制')
      Object.assign(panel.style, { position: 'fixed', bottom: '16px', left: '16px', right: '16px', padding: '16px', color: '#fff', background: '#173c35', zIndex: '2147483647', borderRadius: '12px', boxShadow: '0 4px 24px #0004' })
      const text = document.createElement('p')
      text.textContent = message
      const button = document.createElement('button')
      button.type = 'button'
      button.textContent = buttonText
      Object.assign(button.style, { padding: '8px 16px', marginTop: '8px', cursor: 'pointer', color: '#173c35', background: '#fff', border: '0', borderRadius: '6px' })
      button.onclick = () => { button.disabled = true; window.__sessionAcceptanceConfirmed = true }
      panel.append(text, button)
      document.body.append(panel)
    }, { message, buttonText })
    console.log(`WAITING: ${buttonText}`)
    await page.waitForFunction(() => window.__sessionAcceptanceConfirmed === true, undefined, { timeout: 15 * 60 * 1000 })
    evidence.confirmations.push(buttonText)
    await save()
  }
  async function verifyLogout(loginRequests) {
    evidence.phase = 'waiting-for-logout-confirmation'
    await save()
    await confirm('真实登录和Main保存已通过。请确认当前页面正常，然后开始退出验收。脚本会点击页面右上角真实的“退出登录”，不会直接调用清空IPC。请暂不点击其他业务入口。', '页面正常，开始退出验收')
    const beforeLogout = await collect()
    const clearsBefore = beforeLogout.ipc.filter(r => r.channel === 'auth-session:clear' && r.status === 'ok').length
    evidence.phase = 'logging-out'
    await save()
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL('nexus://app/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    evidence.afterLogout = await snapshot()
    loggedOut(evidence.afterLogout, evidence.afterLogin.mainRevision + 1)
    assert.equal(instance.process().pid, evidence.runtime.pid)
    const cleared = await collect()
    assert.equal(cleared.ipc.filter(r => r.channel === 'auth-session:clear' && r.status === 'ok').length, clearsBefore + 1)
    evidence.phase = 'logout-verified'
    await save()
    console.log('PASS: real logout button, Main clear acknowledged once, Store/Main empty with matching new revision, same Main process.')
    await confirm('真实退出已通过：已回到登录页，Main与Store均为空。请确认登录页正常，再刷新验证不会恢复旧登录。无需再次输入密码。', '已返回登录页，继续刷新验收')
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForURL('nexus://app/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    evidence.afterLogoutRefresh = await snapshot()
    loggedOut(evidence.afterLogoutRefresh, evidence.afterLogout.mainRevision)
    assert.equal(instance.process().pid, evidence.runtime.pid)
    const refreshed = await collect()
    assert.equal(refreshed.requests.filter(r => r.path === '/api/auth/login').length, loginRequests)
    assert.equal(refreshed.ipc.filter(r => r.channel === 'auth-session:write' && r.status === 'ok').length, 1)
    assert.equal(refreshed.ipc.filter(r => r.channel === 'auth-session:clear' && r.status === 'ok').length, clearsBefore + 1)
    assert.equal(evidence.pageHttpRequests, 0)
    evidence.phase = 'logout-refresh-verified'
    await save()
    console.log('PASS: same Main process, login page still shown after reload, empty session and unchanged cleared revision; no new login/write/clear.')
    await confirm('退出后刷新验收通过：仍在登录页，旧会话没有恢复。本轮未关闭或重启Main来掩盖清理结果，也未创建任何任务。确认页面正常后结束验收，窗口会自动关闭。', '刷新后仍是登录页，结束验收')
    evidence.runs.push({ phase: 'login-logout-refresh', runtime: evidence.runtime, ...await collect() })
    auditRequests()
  }
  try {
    evidence.phase = 'launching'
    evidence.runtime = await launch()
    evidence.phase = 'initial-snapshot'
    evidence.initial = await snapshot()
    assert.equal(evidence.initial.initializationStatus, 'ready')
    assert.equal(evidence.initial.storeHasToken, false)
    assert.equal(evidence.initial.mainHasSession, false)
    assert.equal(evidence.initial.mainRevision, 0)
    evidence.phase = 'waiting-for-user-login'
    await save()
    console.log(`Evidence: ${dir}`)
    console.log('WAITING: log in normally in the isolated Nexus window. Do not send credentials in chat. Only login and Application list requests are allowed.')
    await page.waitForURL('nexus://app/applications', { timeout: 15 * 60 * 1000 })
    await page.getByRole('heading', { name: 'Applications', exact: true }).waitFor({ state: 'visible' })
    await expect.poll(async () => (await collect()).requests.some(r => r.path === '/api/application' && r.hasBearer && r.status === 200), { timeout: 20000 }).toBe(true)
    evidence.afterLogin = await snapshot()
    loggedIn(evidence.afterLogin)
    const before = await collect()
    assert.ok(before.requests.some(r => r.path === '/api/auth/login' && r.status === 200 && !r.hasBearer))
    assert.equal(before.ipc.filter(r => r.channel === 'auth-session:write' && r.status === 'ok').length, 1)
    const loginRequests = before.requests.filter(r => r.path === '/api/auth/login').length
    const applicationResponses = before.requests.filter(r => r.path === '/api/application' && r.status === 200).length
    evidence.phase = 'login-verified'
    await save()
    console.log('PASS: real login, Main saved session and Store match; Bearer Application list 200.')
    if (logoutMode) {
      await verifyLogout(loginRequests)
      evidence.passed = true
      evidence.phase = 'complete'
      return
    }
    await confirm('真实登录、Main 保存与列表请求已通过。请确认页面正常，然后点击下方按钮。将只刷新当前页面，不退出 Main；请勿点击退出登录。', '页面正常，开始刷新验收')

    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByRole('heading', { name: 'Applications', exact: true }).waitFor({ state: 'visible' })
    await expect.poll(async () => (await collect()).requests.filter(r => r.path === '/api/application' && r.status === 200).length, { timeout: 20000 }).toBeGreaterThan(applicationResponses)
    evidence.afterRefresh = await snapshot()
    loggedIn(evidence.afterRefresh)
    assert.equal(instance.process().pid, evidence.runtime.pid)
    assert.equal(evidence.afterRefresh.mainRevision, evidence.afterLogin.mainRevision)
    assert.equal(evidence.afterRefresh.mainExpiresAt, evidence.afterLogin.mainExpiresAt)
    const refreshed = await collect()
    assert.equal(refreshed.requests.filter(r => r.path === '/api/auth/login').length, loginRequests)
    assert.equal(refreshed.ipc.filter(r => r.channel === 'auth-session:write' && r.status === 'ok').length, 1)
    assert.ok(refreshed.requests.every(r => r.allowed))
    evidence.phase = 'refresh-verified'
    await save()
    console.log('PASS: same Main PID, refreshed Store restored session/revision/expiry; no new login POST or session write; Application list 200.')
    await confirm('刷新恢复已通过：仍处于登录状态，没有重新登录，有效期没有延长。请确认页面正常。下一步将关闭整个测试进程，再使用同一隔离目录重开，预期回到登录页。', '刷新页面正常，继续重开验收')

    evidence.runs.push({ phase: 'login-and-refresh', runtime: evidence.runtime, ...await collect() })
    await instance.close()
    instance = null
    evidence.reopenedRuntime = await launch()
    evidence.afterRestart = await snapshot()
    assert.notEqual(evidence.reopenedRuntime.pid, evidence.runtime.pid)
    assert.equal(evidence.reopenedRuntime.userData, evidence.runtime.userData)
    assert.equal(evidence.afterRestart.initializationStatus, 'ready')
    assert.equal(evidence.afterRestart.storeHasToken, false)
    assert.equal(evidence.afterRestart.mainHasSession, false)
    assert.equal(evidence.afterRestart.mainRevision, 0)
    assert.equal(evidence.pageHttpRequests, 0)
    evidence.phase = 'restart-verified'
    await save()
    console.log('PASS: new Main process, same isolated profile, login page shown with empty memory session.')
    await confirm('整进程重开后回到登录页，说明当前会话未跨进程持久化。本轮技术检查通过。无需再次输入密码；确认登录页正常后结束验收。', '登录页正常，结束验收')
    evidence.runs.push({ phase: 'restart', runtime: evidence.reopenedRuntime, ...await collect() })
    auditRequests()
    evidence.passed = true
    evidence.phase = 'complete'
  } catch (error) {
    evidence.failed = true
    const known = ['Mounted user Store unavailable', 'Trusted session read rejected']
    evidence.error = {
      name: error?.name ?? 'UnknownError',
      diagnostic: known.find(message => String(error?.message).includes(message)) ?? 'CHECK_FAILED',
      location: String(error?.stack ?? '').split('\n').find(line => line.includes('test-session-refresh-acceptance.cjs:'))?.trim() ?? null,
    }
    console.error(`FAIL: session acceptance incomplete at phase ${evidence.phase} (${evidence.error.name}, ${evidence.error.diagnostic}); no credentials recorded.`)
    process.exitCode = 1
  } finally {
    if (instance) {
      try { evidence.finalObservation = await collect() } catch {}
      await instance.close().catch(() => {})
    }
    await save()
    console.log(`Evidence: ${dir}`)
  }
})().catch(() => { console.error('Session acceptance setup failed'); process.exitCode = 1 })
