const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFileSync } = require('node:child_process')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')
const live = process.argv.includes('--live')

;(async () => {
  const directory = await fs.mkdtemp(path.join(root, '.runtime', live ? 'auth-live-' : 'auth-controlled-'))
  const email = `desktop-auth-${crypto.randomUUID()}@example.test`
  const password = crypto.randomBytes(24).toString('base64url') + 'aA1!'
  const evidence = { passed: false, liveBackend: live, clientTtlShortenedForTrigger: true,
    serverJwtNaturalExpiryVerified: false, humanVisualConfirmation: false, cases: [], testAccountCreated: false }
  let instance, page
  const save = () => fs.writeFile(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2))
  const audit = () => instance.evaluate(() => {
    const c = globalThis.__renewalAcceptance
    return { requests: c.requests, rotations: c.rotations, rtChanged: c.rtChanged,
      rotationIdentityStable: c.rotationIdentityStable, blockedRendererNetwork: c.blockedRendererNetwork }
  })
  const snapshot = () => page.evaluate(async () => {
    const main = await window.nexusDesktop.readAuthSession()
    const store = document.querySelector('#app')?.__vue_app__?.config.globalProperties.$pinia?._s.get('userStore')
    if (!store) throw Error('Store unavailable')
    return { revision: main.authState.revision, hasSession: main.authState.session !== null,
      expiryMatches: main.authState.session?.expiresAt === store.expiresAt,
      sessionId: main.authState.session?.sessionId ?? null,
      sessionDeadline: main.authState.session?.sessionExpiresAt ?? null,
      noRT: !JSON.stringify(main).includes('refreshToken') && !JSON.stringify(store.$state).includes('refreshToken'),
      storeEmpty: store.accessToken === '', hasWarning: !!store.sessionNotice }
  })
  const signIn = async () => {
    await page.getByPlaceholder('请输入邮箱').fill(email)
    await page.getByPlaceholder('请输入密码').fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL('nexus://app/applications')
    await page.getByRole('heading', { name: 'Applications', exact: true }).waitFor()
  }
  try {
    if (live) {
      // Verify the active process, not just a potentially overridden local YAML.
      const listener = execFileSync('lsof', ['-nP', '-iTCP:8080', '-sTCP:LISTEN'], { encoding: 'utf8' })
      const pid = listener.match(/^java\s+(\d+)/m)?.[1]
      const log = (await fs.readFile(path.join(root, '../logger/nexus.log'), 'utf8')).slice(-500000)
      const starts = [...log.matchAll(/Starting\s+\w+[^\n]*?PID\s+(\d+)/g)]
      assert.ok(pid && starts.length && starts.at(-1)[1] === pid, 'Active backend must match the latest startup log')
      const startup = log.slice(starts.at(-1).index)
      const schema = [...startup.matchAll(/Database:\s+jdbc:mysql:\/\/[^/\s]+\/([^?\s]+)/g)].at(-1)?.[1]
      assert.equal(schema, 'nexus_test', 'Refuse to write outside the verified dedicated test schema')
      evidence.verifiedSchema = schema
      const registered = await fetch('http://localhost:8080/api/auth/register', { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password, displayName: 'Desktop auth acceptance' }), redirect: 'error' })
      assert.ok(registered.status === 200 || registered.status === 201, 'Dedicated test-account registration failed')
      await registered.body?.cancel()
      evidence.testAccountCreated = true
    }
    const env = { ...process.env, NEXUS_RENEWAL_ACCEPTANCE_DIR: directory,
      NEXUS_RENEWAL_ACCEPTANCE_LIVE: live ? '1' : '0', NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'),
      args: [path.join(__dirname, 'session-renewal-acceptance-main.cjs')], env, timeout: 25000 })
    page = await instance.firstWindow()
    const errors = []
    page.on('pageerror', error => errors.push(error.name))
    await page.waitForURL('nexus://app/login')
    await page.getByRole('button', { name: '登录', exact: true }).waitFor()
    await signIn()
    const first = await snapshot()
    assert.ok(first.hasSession && first.expiryMatches && first.noRT)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByRole('heading', { name: 'Applications', exact: true }).waitFor()
    assert.deepEqual(await snapshot(), first)
    evidence.cases.push({ name: 'login-and-page-reload', passed: true })
    await page.waitForFunction(() => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      return store.expiresAt <= Date.now()
    }, undefined, { timeout: 15000 })
    await page.getByRole('link', { name: '开发文档', exact: true }).click()
    await page.evaluate(async () => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      const context = { operationVersion: store.operationVersion, mainRevision: store.mainRevision }
      await Promise.all([store.ensureSession(context), store.ensureSession(context)])
    })
    await page.waitForURL('nexus://app/docs')
    const renewed = await snapshot()
    assert.equal(renewed.sessionId, first.sessionId)
    assert.equal(renewed.sessionDeadline, first.sessionDeadline)
    assert.equal(renewed.revision, first.revision + 1)
    assert.ok(renewed.expiryMatches && renewed.noRT)
    assert.equal((await audit()).rotations, 1)
    assert.ok((await audit()).rtChanged && (await audit()).rotationIdentityStable)
    evidence.cases.push({ name: 'automatic-route-renewal-with-concurrent-callers', passed: true })
    // Return to the console with the new AT, then use its real logout button.
    await page.evaluate(() => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/applications'))
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL('nexus://app/login')
    assert.equal((await snapshot()).storeEmpty, true)
    assert.equal((await snapshot()).hasSession, false)
    assert.equal(await instance.evaluate(() => globalThis.__renewalAcceptance.checkRevoked()), true)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '登录', exact: true }).waitFor()
    assert.equal((await snapshot()).hasSession, false)
    evidence.cases.push({ name: 'server-logout-revokes-at-and-reload-stays-empty', passed: true })

    await signIn()
    await instance.evaluate(() => { globalThis.__renewalAcceptance.dropLogoutOnce = true })
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL('nexus://app/login')
    await expect(page.locator('.form-wrap .el-alert')).toContainText('服务端撤销未确认')
    assert.equal((await snapshot()).hasSession, false)
    assert.equal(await instance.evaluate(() => globalThis.__renewalAcceptance.checkRevoked()), true)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByRole('button', { name: '登录', exact: true }).waitFor()
    await expect(page.locator('.form-wrap .el-alert')).toContainText('服务端撤销未确认')
    evidence.cases.push({ name: 'lost-logout-ack-clears-locally-and-warns', passed: true, acknowledgementLossInjected: true })

    await signIn()
    await instance.evaluate(() => { globalThis.__renewalAcceptance.dropRefreshOnce = true })
    // Explicitly force the same production Main/Store path to test a lost refresh response.
    await page.evaluate(async () => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('userStore')
      const result = await store.ensureSession({ operationVersion: store.operationVersion, mainRevision: store.mainRevision }, true)
      if (result.status !== 'reauthenticate') throw Error('Expected reauthentication')
      await document.querySelector('#app').__vue_app__.config.globalProperties.$router.replace('/login')
    })
    assert.equal((await snapshot()).hasSession, false)
    await expect(page.locator('.form-wrap .el-alert')).toContainText('续期')
    evidence.cases.push({ name: 'lost-refresh-response-never-retries-old-rt', passed: true, responseLossInjected: true })
    evidence.finalSessionCleanupConfirmed = await instance.evaluate(() => globalThis.__renewalAcceptance.cleanupSession())
    assert.equal(evidence.finalSessionCleanupConfirmed, true)
    evidence.audit = await audit()
    assert.equal(evidence.audit.requests.filter(r => r.path === '/api/auth/refresh').length, 2)
    assert.equal(evidence.audit.blockedRendererNetwork, 0)
    assert.deepEqual(errors, [])
    evidence.pageErrors = errors
    await instance.close(); instance = null
    instance = await _electron.launch({ executablePath: require('electron'),
      args: [path.join(__dirname, 'session-renewal-acceptance-main.cjs')], env, timeout: 25000 })
    page = await instance.firstWindow()
    await page.waitForURL('nexus://app/login')
    await page.getByRole('button', { name: '登录', exact: true }).waitFor()
    assert.equal((await snapshot()).hasSession, false)
    assert.equal((await audit()).requests.length, 0)
    evidence.cases.push({ name: 'complete-process-restart-does-not-remember-login', passed: true })
    evidence.passed = true
    await save()
    console.log(`PASS: ${live ? 'real backend' : 'fixed mock'} login/renewal/logout; evidence ${directory}`)
  } catch (error) {
    evidence.error = { name: error.name, location: String(error.stack).split('\n').find(line => line.includes('test-session-renewal-acceptance.cjs:'))?.trim() }
    await save()
    // Assertion details can contain credentials; output only a safe failure location.
    console.error('FAIL: auth acceptance; see safe evidence location', directory)
    process.exitCode = 1
  } finally {
    if (instance) {
      try { await instance.evaluate(() => globalThis.__renewalAcceptance.cleanupSession()) } catch {}
      await instance.close()
    }
    await save()
  }
})().catch(() => { console.error('Auth acceptance setup failed'); process.exitCode = 1 })
