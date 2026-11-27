const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')
;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const directory = await fs.mkdtemp(path.join(root, '.runtime/robustness-'))
  const env = { ...process.env, NEXUS_ROBUSTNESS_DIR: directory, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
  delete env.ELECTRON_RUN_AS_NODE
  let instance
  try {
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'robustness-main.cjs')], env, timeout: 30000 })
    const page = await instance.firstWindow()
    const signIn = async name => {
      await page.getByPlaceholder('请输入邮箱').fill(`${name}@example.test`)
      await page.getByPlaceholder('请输入密码').fill('fixture-password')
      await page.getByRole('button', { name: '登录', exact: true }).click()
      await page.waitForURL('nexus://app/applications')
    }
    const submit = () => page.evaluate(async () => {
      const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('ScreenshotTrackingStore')
      await store.submitDesktopScreenshot(`nxk_v1_123e4567-e89b-42d3-a456-426614174000_${'A'.repeat(43)}`, 'https://blog.csdn.net/article')
    })
    await page.waitForURL('nexus://app/login')
    await signIn('a')
    const oldRevision = (await page.evaluate(() => window.nexusDesktop.readAuthSession())).authState.revision
    await submit()
    await expect.poll(() => instance.evaluate(() => globalThis.__robustness.polls.length)).toBe(1)
    assert.match((await instance.evaluate(() => globalThis.__robustness.submissions))[0], /^\d{13}\./)
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.waitForURL('nexus://app/login')
    assert.equal((await page.evaluate(() => window.nexusDesktop.readScreenshotTracking())).snapshot.traceStatus, 'IDLE')
    assert.equal(await instance.evaluate(() => globalThis.__robustness.polls[0].aborted), true)
    await signIn('b')
    await instance.evaluate(() => globalThis.__robustness.polls[0].release())
    await expect.poll(async () => (await page.evaluate(() => window.nexusDesktop.readScreenshotTracking())).snapshot.traceStatus).toBe('IDLE')
    assert.equal(await instance.evaluate(() => globalThis.__robustness.imageReads), 0)
    await submit()
    await expect.poll(() => instance.evaluate(() => globalThis.__robustness.polls.length)).toBe(2)
    const rejected = await page.evaluate(revision => window.nexusDesktop.logoutAuthSession({ expectedRevision: revision }), oldRevision)
    assert.equal(rejected.status, 'failed')
    assert.equal((await page.evaluate(() => window.nexusDesktop.readScreenshotTracking())).snapshot.traceStatus, 'POLLING')
    await page.evaluate(() => window.nexusDesktop.stopScreenshotTracking())
    await instance.evaluate(() => globalThis.__robustness.polls[1].release())
    const evidence = { passed: true, realBackend: false, mainForwardsIdempotencyKey: true, identityChangeClearsTracking: true,
      latePollCannotRestoreOldTask: true, staleLogoutCannotStopNewTask: true }
    await fs.writeFile(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(JSON.stringify(evidence))
  } finally { if (instance) await instance.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
