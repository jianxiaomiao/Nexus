const fs = require('node:fs/promises')
const path = require('node:path')
const assert = require('node:assert/strict')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')

const root = path.resolve(__dirname, '..')
const scenarios = ['router-expired', 'request-expired', 'current-401', 'stale-before-save', 'stale-after-save', 'lost-clear-ack']
const selected = process.argv.find(arg => arg.startsWith('--case='))?.slice('--case='.length)
if (selected && !scenarios.includes(selected)) throw Error('Unknown controlled session test case')

;(async () => {
  await fs.access(path.join(root, 'dist/renderer/index.html'))
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const directory = await fs.mkdtemp(path.join(root, '.runtime', 'session-invalidation-controlled-'))
  const evidence = {
    passed: false, phase: 'setup', backendMode: 'in-process-fixed-responses',
    realBackendCalls: 0, humanVisualConfirmation: false, cases: [],
  }
  const save = () => fs.writeFile(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2))
  let instance, page, current

  const observe = () => instance.evaluate(() => {
    const c = globalThis.__invalidationControlled
    return { requests: c.requests, ipc: c.ipc, blockedRendererRequests: c.blockedRendererRequests }
  })

  async function localSnapshot() {
    return page.evaluate(() => {
      const app = document.querySelector('#app')?.__vue_app__
      const store = app?.config.globalProperties.$pinia?._s.get('userStore')
      if (!store) throw Error('Mounted Store unavailable')
      return {
        path: location.pathname, ready: store.initializationStatus === 'ready',
        operationVersion: store.operationVersion, revision: store.mainRevision,
        hasSession: !!store.accessToken, expiresAt: store.expiresAt,
        clearing: store.clearing, hasError: !!store.clearError,
        noNode: typeof window.require === 'undefined' && typeof window.process === 'undefined',
      }
    })
  }

  async function snapshot() {
    const local = await localSnapshot()
    const main = await page.evaluate(async () => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      const result = await window.nexusDesktop.readAuthSession()
      if (result.status !== 'ok') throw Error('Trusted Main read rejected')
      return {
        revision: result.authState.revision, hasSession: result.authState.session !== null,
        expiresAt: result.authState.session?.expiresAt ?? null,
        matches: store.accessToken === result.authState.session?.accessToken,
      }
    })
    return { local, main }
  }

  function validSession(state, revision) {
    assert.equal(state.local.path, '/applications')
    assert.equal(state.local.ready, true)
    assert.equal(state.local.hasSession, true)
    assert.equal(state.main.hasSession, true)
    assert.equal(state.main.matches, true)
    assert.equal(state.local.revision, revision)
    assert.equal(state.main.revision, revision)
    assert.equal(state.local.expiresAt, state.main.expiresAt)
    assert.ok(state.local.expiresAt > Date.now())
    assert.equal(state.local.hasError, false)
    assert.equal(state.local.noNode, true)
  }

  async function emptySession(revision) {
    await page.waitForURL('nexus://app/login')
    await page.getByRole('button', { name: '登录', exact: true }).waitFor({ state: 'visible' })
    const state = await snapshot()
    assert.equal(state.local.ready, true)
    assert.equal(state.local.hasSession, false)
    assert.equal(state.main.hasSession, false)
    assert.equal(state.local.revision, revision)
    assert.equal(state.main.revision, revision)
    assert.equal(state.local.expiresAt, 0)
    assert.equal(state.local.clearing, false)
    assert.equal(state.local.hasError, false)
    return state
  }

  async function panel(label) {
    await page.evaluate(label => {
      let panel = document.querySelector('#controlled-session-panel')
      if (!panel) {
        panel = document.createElement('aside')
        panel.id = 'controlled-session-panel'
        panel.setAttribute('aria-label', '受控会话验收')
        Object.assign(panel.style, {
          position: 'fixed', top: '75px', right: '16px', maxWidth: '290px', padding: '12px',
          color: '#fff', background: '#173c35', zIndex: '2147483647', borderRadius: '8px', pointerEvents: 'none',
        })
        document.body.append(panel)
      }
      panel.textContent = `受控会话验收：${label}。使用假凭据自动测试，不连接真实后端。`
    }, label)
  }

  async function launch(name) {
    const caseDirectory = path.join(directory, name)
    await fs.mkdir(caseDirectory)
    const env = {
      ...process.env, NEXUS_INVALIDATION_TEST_DIR: caseDirectory,
      NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0',
    }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({
      executablePath: require('electron'), args: [path.join(__dirname, 'session-invalidation-controlled-main.cjs')],
      env, timeout: 25000,
    })
    current.pid = instance.process().pid
    page = await instance.firstWindow()
    current.pageErrors = []
    page.on('pageerror', error => current.pageErrors.push(error.name))
    await page.waitForURL('nexus://app/login')
    await page.getByRole('button', { name: '登录', exact: true }).waitFor({ state: 'visible' })
    const runtime = await instance.evaluate(({ app }) => ({ userData: app.getPath('userData'), packaged: app.isPackaged }))
    assert.equal(runtime.userData, path.join(caseDirectory, 'profile'))
    assert.equal(runtime.packaged, false)
    current.runtime = runtime
    await expect.poll(() => instance.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isVisible()), { timeout: 20000 }).toBe(true)
    await panel(name)
  }

  async function login(shortExpiry) {
    await instance.evaluate((_electron, ttl) => { globalThis.__invalidationControlled.ttlSeconds = ttl }, shortExpiry ? 10 : 600)
    await page.getByPlaceholder('请输入邮箱').fill('controlled-session@example.test')
    await page.getByPlaceholder('请输入密码').fill('CONTROLLED_TEST_ONLY')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL('nexus://app/applications')
    await page.getByRole('article').filter({ hasText: '受控会话验收应用' }).waitFor({ state: 'visible' })
    current.afterLogin = await snapshot()
    validSession(current.afterLogin, 1)
    const observed = await observe()
    assert.equal(observed.ipc.filter(r => r.channel === 'auth-session:write' && r.status === 'ok').length, 1)
  }

  async function queryFromPage() {
    // Real page event -> listApplications -> production Axios; no raw fetch or direct clear.
    await page.getByRole('button', { name: 'increase number', exact: true }).click()
  }

  async function verifyCleared(name) {
    await page.waitForURL('nexus://app/login')
    await page.getByRole('button', { name: '登录', exact: true }).waitFor({ state: 'visible' })
    // Inspect the production recovery read before our own diagnostic read can add one.
    let observed = await observe()
    const clears = observed.ipc.filter(r => r.channel === 'auth-session:clear')
    assert.equal(clears.length, 1)
    assert.equal(clears[0].status, 'ok')
    assert.equal(clears[0].expectedRevision, 1)
    assert.equal(clears[0].revision, 2)
    if (name === 'lost-clear-ack') {
      assert.equal(clears[0].lostAcknowledgement, true)
      const index = observed.ipc.indexOf(clears[0])
      assert.equal(observed.ipc[index + 1]?.channel, 'auth-session:read')
      assert.equal(observed.ipc[index + 1]?.hasSession, false)
    }
    current.afterClear = await emptySession(2)
    const requestsBeforeRefresh = observed.requests.length
    await page.reload({ waitUntil: 'domcontentloaded' })
    current.afterRefresh = await emptySession(2)
    assert.equal(instance.process().pid, current.pid)
    observed = await observe()
    assert.equal(observed.requests.length, requestsBeforeRefresh)
    assert.equal(observed.ipc.filter(r => r.channel === 'auth-session:clear').length, 1)
  }

  async function verifyExpired(name) {
    const before = await observe()
    await panel(name === 'router-expired' ? '等待自然到期，随后切换文档路由' : '等待自然到期，随后点击每页条数查询')
    // Do not call readAuthSession after expiry here: Main read itself expires sessions.
    await page.waitForFunction(() => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      return !!store.accessToken && store.expiresAt <= Date.now()
    }, undefined, { timeout: 15000 })
    current.atExpiry = await localSnapshot()
    assert.equal(current.atExpiry.hasSession, true)
    if (name === 'router-expired') {
      await page.getByRole('link', { name: '开发文档', exact: true }).click()
    } else {
      await queryFromPage()
    }
    await verifyCleared(name)
    const after = await observe()
    assert.equal(after.requests.length, before.requests.length) // Expired query never reaches Main fetch.
  }

  async function verifyCurrent401(name) {
    const before = await observe()
    await instance.evaluate((_electron, lostAck) => {
      const c = globalThis.__invalidationControlled
      c.mode = 'unauthorized'
      c.loseNextClearAck = lostAck
    }, name === 'lost-clear-ack')
    await queryFromPage()
    await verifyCleared(name)
    const after = await observe()
    assert.equal(after.requests.length, before.requests.length + 1)
    assert.equal(after.requests.at(-1).status, 401)
    assert.equal(after.requests.at(-1).hasBearer, true)
  }

  async function verifyStale401(name) {
    await instance.evaluate(() => { globalThis.__invalidationControlled.mode = 'deferred401' })
    await queryFromPage()
    await expect.poll(() => instance.evaluate(() => !!globalThis.__invalidationControlled.release401)).toBe(true)
    current.beforeNewIntent = await snapshot()
    // A second login cannot be entered through the public login route while signed in.
    // Drive the real Store's login intent/write boundary with fixed test data instead.
    await page.evaluate(() => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      window.__controlledLoginAttempt = store.beginLogin()
    })
    if (name === 'stale-after-save') await completeControlledLogin()
    current.beforeRelease = await snapshot()
    assert.ok(current.beforeRelease.local.operationVersion > current.beforeNewIntent.local.operationVersion)
    validSession(current.beforeRelease, name === 'stale-after-save' ? 2 : 1)
    await instance.evaluate(() => { globalThis.__invalidationControlled.release401() })
    await page.locator('.notice.error-notice').waitFor({ state: 'visible' })
    current.afterOld401 = await snapshot()
    assert.deepEqual(current.afterOld401, current.beforeRelease)
    const observed = await observe()
    assert.equal(observed.ipc.filter(r => r.channel === 'auth-session:clear').length, 0)
    assert.equal(observed.requests.at(-1).status, 401)
    if (name === 'stale-before-save') await completeControlledLogin()
    await instance.evaluate(() => { globalThis.__invalidationControlled.mode = 'success' })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByRole('article').filter({ hasText: '受控会话验收应用' }).waitFor({ state: 'visible' })
    current.afterRefresh = await snapshot()
    validSession(current.afterRefresh, 2)
    assert.equal(instance.process().pid, current.pid)
  }

  async function completeControlledLogin() {
    const result = await page.evaluate(async () => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      return store.completeLogin(window.__controlledLoginAttempt, {
        accessToken: 'CONTROLLED_REPLACEMENT_SESSION_ONLY', tokenType: 'Bearer', expiresInSeconds: 600,
      })
    })
    assert.equal(result.status, 'ok')
  }

  try {
    for (const name of selected ? [selected] : scenarios) {
      evidence.phase = name
      current = { name, passed: false }
      evidence.cases.push(current)
      await save()
      try {
        await launch(name)
        await login(name.endsWith('expired'))
        if (name.endsWith('expired')) await verifyExpired(name)
        else if (name.startsWith('stale-')) await verifyStale401(name)
        else await verifyCurrent401(name)
        current.observation = await observe()
        assert.ok(current.observation.requests.every(r => r.allowed && !r.hasContextHeader))
        assert.equal(current.observation.blockedRendererRequests, 0)
        assert.deepEqual(current.pageErrors, [])
        current.passed = true
        console.log(`PASS: ${name}`)
      } finally {
        if (instance) {
          try { current.observation = await observe() } catch {}
          await instance.close()
          current.closed = true
          instance = null
        }
        await save()
      }
    }
    evidence.passed = true
    evidence.phase = 'complete'
  } catch (error) {
    evidence.error = {
      name: error?.name ?? 'UnknownError',
      location: String(error?.stack ?? '').split('\n').find(line => line.includes('test-session-invalidation-controlled.cjs:'))?.trim() ?? null,
    }
    console.error(`FAIL: controlled session test at ${evidence.phase} (${evidence.error.name}); no credentials recorded`)
    process.exitCode = 1
  } finally {
    await save()
    console.log(`Evidence: ${directory}`)
  }
})().catch(() => { console.error('Controlled session setup failed'); process.exitCode = 1 })
