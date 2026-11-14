const fs = require('node:fs/promises')
const path = require('node:path')
const assert = require('node:assert/strict')
const { createHash } = require('node:crypto')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'utils-acceptance-'))
  let instance
  const evidence = { passed: false, stage: 'launch', rendererHttpRequests: 0 }
  try {
    const env = { ...process.env, NEXUS_UTILS_ACCEPTANCE_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'utils-acceptance-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    page.on('request', request => { if (/^https?:/.test(request.url())) evidence.rendererHttpRequests++ })
    await page.waitForURL('nexus://app/login')
    evidence.stage = 'anonymous-boundary'
    evidence.anonymous = await page.evaluate(async () => {
      const response = await fetch('/v1/utils/uuid')
      const data = await response.json()
      return { status: response.status, code: data.code }
    })
    assert.equal(evidence.anonymous.status, 401)
    console.log('PASS: real /v1/ anonymous request returns 401 through nexus protocol')
    evidence.stage = 'waiting-login'
    console.log('WAITING: log in inside the isolated window; then enter the existing API Key on the playground. No credentials in chat.')
    await page.waitForURL('nexus://app/applications', { timeout: 15 * 60 * 1000 })
    await page.getByRole('link', { name: 'API 调试台', exact: true }).click()
    await page.getByRole('button', { name: '生成 UUID', exact: true }).click()
    await page.locator('#playground-key').waitFor({ state: 'visible' })
    await page.evaluate(() => {
      window.__utilsAcceptanceReady = false
      const panel = document.createElement('aside')
      panel.id = 'utils-acceptance-ready'
      Object.assign(panel.style, { position: 'fixed', bottom: '20px', left: '20px', right: '20px', zIndex: '2147483647', padding: '18px', background: '#173c35', color: 'white', borderRadius: '12px' })
      const text = document.createElement('p')
      text.textContent = '请在 API Key 输入框粘贴已有的有效完整 Key，然后点击下方按钮。脚本将各调用一次 UUID 和 Hash（正常消耗配额及记录 Usage），不创建截图。请勿点击原页面发送按钮。通过后30秒自动关闭窗口。'
      const button = document.createElement('button')
      button.textContent = 'Key 已填写，开始 UUID / Hash 验收'
      button.onclick = () => {
        if (!document.querySelector('#playground-key')?.value.trim()) { text.textContent = '请先填写完整 API Key。'; return }
        window.__utilsAcceptanceReady = true
        button.disabled = true
      }
      panel.append(text, button)
      document.body.append(panel)
    })
    evidence.stage = 'waiting-key'
    console.log('WAITING: paste API Key in its field, then click the test-only readiness button. Do not click Send yourself.')
    await page.waitForFunction(() => window.__utilsAcceptanceReady === true, null, { timeout: 15 * 60 * 1000 })
    await page.evaluate(() => document.querySelector('#utils-acceptance-ready')?.remove())
    evidence.stage = 'uuid'
    const uuidResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/utils/uuid', { timeout: 40000 })
    await page.getByRole('button', { name: '发送请求', exact: true }).click()
    const uuidHttp = await uuidResponse
    evidence.uuidStatus = uuidHttp.status()
    assert.equal(evidence.uuidStatus, 200)
    const uuid = await uuidHttp.json()
    assert.equal(uuid.code, 'SUCCESS')
    evidence.uuidValid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(uuid.data?.uuid ?? '')
    assert.equal(evidence.uuidValid, true)
    await expect(page.locator('.playground-status')).toHaveText('200 OK')
    console.log('PASS: real UUID 200, SUCCESS envelope and valid version-4 UUID')
    evidence.stage = 'hash'
    await page.getByRole('button', { name: '计算 Hash', exact: true }).click()
    const value = 'Nexus Electron 验收 2026-11-14'
    await page.locator('#playground-value').fill(value)
    const hashResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/utils/hash', { timeout: 40000 })
    await page.getByRole('button', { name: '发送请求', exact: true }).click()
    const hashHttp = await hashResponse
    evidence.hashStatus = hashHttp.status()
    assert.equal(evidence.hashStatus, 200)
    const hash = await hashHttp.json()
    assert.equal(hash.code, 'SUCCESS')
    evidence.hashMatchesUtf8Sha256 = hash.data?.hashContent === createHash('sha256').update(value, 'utf8').digest('hex')
    assert.equal(evidence.hashMatchesUtf8Sha256, true)
    await expect(page.locator('.playground-status')).toHaveText('200 OK')
    await expect(page.locator('.playground-response code')).toContainText(hash.data.hashContent)
    const control = await instance.evaluate(() => globalThis.__utilsAcceptance)
    evidence.control = control
    for (const endpoint of ['uuid', 'hash']) {
      const records = control.requests.filter(record => record.path === `/v1/utils/${endpoint}` && record.authKind === 'ApiKey')
      assert.equal(records.length, 1); assert.equal(records[0].status, 200)
    }
    assert.equal(control.blocked, 0)
    assert.equal(control.requests.some(record => record.path.startsWith('/v1/') && record.authKind === 'Bearer'), false)
    assert.equal(evidence.rendererHttpRequests, 0)
    evidence.stage = 'passed'
    evidence.passed = true
    console.log('PASS: real UTF-8 SHA256 matches local calculation; exactly one authenticated call per endpoint; no screenshot requests or Renderer HTTP/HTTPS')
    await page.evaluate(() => {
      const banner = document.createElement('div')
      banner.textContent = 'UUID / Hash 真实验收通过：UUID格式正确，UTF-8 SHA256匹配。未创建截图任务。窗口将在30秒后自动关闭。'
      Object.assign(banner.style, { position:'fixed', bottom:'20px', left:'20px', right:'20px', zIndex:'2147483647', padding:'20px', background:'#173c35', color:'white', borderRadius:'12px' })
      document.body.append(banner)
    })
    await new Promise(resolve => setTimeout(resolve, 30000))
  } catch (error) {
    evidence.errorType = error.name
    console.error(`FAIL: utils acceptance at stage ${evidence.stage}; no automatic retries`)
    process.exitCode = 1
  } finally {
    if (instance) {
      try { evidence.control = await instance.evaluate(() => globalThis.__utilsAcceptance) } catch {}
      await instance.close().catch(() => {})
    }
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(`Evidence: ${dir}`)
  }
})().catch(() => { console.error('Utils acceptance setup failed'); process.exitCode = 1 })
