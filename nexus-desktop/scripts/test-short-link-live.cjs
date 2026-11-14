const fs = require('node:fs/promises')
const path = require('node:path')
const assert = require('node:assert/strict')
const { _electron, expect } = require('../../nexus-console/node_modules/@playwright/test')
const root = path.resolve(__dirname, '..')

;(async () => {
  await fs.mkdir(path.join(root, '.runtime'), { recursive: true })
  const dir = await fs.mkdtemp(path.join(root, '.runtime', 'short-link-live-'))
  const evidence = { passed: false, stage: 'launch', checks: [], humanConfirmed: [] }
  const resumePath = process.argv[2] ? path.resolve(process.argv[2]) : null
  const previous = resumePath ? JSON.parse(await fs.readFile(resumePath, 'utf8')) : null
  let instance
  try {
    const env = { ...process.env, NEXUS_SHORT_LINK_LIVE_DIR: dir, NEXUS_DESKTOP_SMOKE: '0', NEXUS_DESKTOP_DIAGNOSE: '0' }
    if (resumePath) env.NEXUS_SHORT_LINK_LIVE_RESUME = resumePath
    delete env.ELECTRON_RUN_AS_NODE
    instance = await _electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'short-link-live-main.cjs')], env, timeout: 20000 })
    const page = await instance.firstWindow()
    page.setDefaultTimeout(20000)
    await page.waitForURL('nexus://app/login')
    evidence.stage = 'waiting-login'
    console.log('WAITING: log in inside the isolated window. This test creates only two short links, never screenshots.')
    await page.waitForURL('nexus://app/applications', { timeout: 15 * 60 * 1000 })
    await page.getByRole('link', { name: 'API 调试台', exact: true }).click()
    await page.getByRole('button', { name: '短链接', exact: true }).click()
    async function panel(text, label, needsKey = false) {
      await page.evaluate(({ text, label, needsKey }) => {
        document.querySelector('#short-live-panel')?.remove()
        window.__shortLiveConfirmed = false
        const box = document.createElement('aside')
        box.id = 'short-live-panel'
        Object.assign(box.style, { position: 'fixed', bottom: '16px', left: '16px', right: '16px', zIndex: '2147483647', padding: '16px', background: '#173c35', color: 'white', borderRadius: '12px' })
        const description = document.createElement('p'); description.textContent = text
        const button = document.createElement('button'); button.textContent = label
        button.onclick = () => {
          if (needsKey && !document.querySelector('#playground-key')?.value.trim()) { description.textContent = '请先填写已有的完整 API Key。'; return }
          window.__shortLiveConfirmed = true; button.disabled = true
        }
        box.append(description, button); document.body.append(box)
      }, { text, label, needsKey })
    }
    async function waitConfirmation() {
      await page.waitForFunction(() => window.__shortLiveConfirmed === true, null, { timeout: 15 * 60 * 1000 })
      await page.evaluate(() => document.querySelector('#short-live-panel')?.remove())
    }
    evidence.stage = 'waiting-key'
    await panel(previous
      ? '继续上轮验收：第一条短链已保留，请填写同一枚完整 API Key。此次只补建第二条10秒有效的短链，不重复创建第一条。随后真实打开浏览器三次，测试后保留禁用/过期记录；不创建截图。'
      : '请填写已有的完整 API Key，再点击开始。脚本会创建两条指向 https://weibo.com/ 的验收短链（正常消耗配额），其中一条稍后禁用，另一条10秒后过期。将真实打开浏览器三次；请勿手动发送、创建或删除。测试后保留这两条记录，不创建截图任务。', 'Key 已填写，开始短链验收', true)
    console.log('WAITING: paste existing API Key, then click the test-only start button. Two new links, three browser opens; no screenshots.')
    await waitConfirmation()
    const stamp = Date.now()
    async function createLink(suffix, shortLived) {
      await page.getByLabel('短链名称', { exact: true }).fill(`Electron跳转验收-${suffix}-${stamp}`)
      await page.getByLabel('原始 HTTPS 地址').fill('https://weibo.com/')
      if (shortLived) {
        evidence.step = 'choose-short-duration'
        await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '有效期' }) }).click()
        await page.getByRole('option', { name: '10 秒', exact: true }).click()
      }
      const pending = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/short-links' && response.request().method() === 'POST')
      await page.getByRole('button', { name: '发送请求', exact: true }).click()
      const response = await pending
      assert.equal(response.status(), 200)
      const json = await response.json()
      assert.equal(json.code, 'SUCCESS')
      await expect(page.locator('.playground-created-link')).toBeVisible()
      return json.data
    }
    evidence.stage = 'create-active'
    const active = previous
      ? { ...previous.control.links[0], originalUrl: 'https://weibo.com/' }
      : await createLink('正常与禁用', false)
    evidence.stage = 'create-expiring'
    const expiring = await createLink('到期', true)
    console.log('PASS: exactly two real short links created; no automatic creation retry')
    evidence.stage = 'management'
    await page.getByRole('link', { name: '管理这把 Key 的短链', exact: true }).click()
    const table = page.locator('.short-links-table')
    const activeRow = table.getByRole('row').filter({ hasText: active.shortCode })
    const expiredRow = table.getByRole('row').filter({ hasText: expiring.shortCode })
    await expect(activeRow).toBeVisible(); await expect(expiredRow).toBeVisible()
    async function checkHttp(link, expected) {
      const response = await fetch(`http://localhost:8080/s/${link.shortCode}`, { redirect: 'manual', signal: AbortSignal.timeout(5000) })
      const location = response.headers.get('location')
      const record = { status: response.status, expected, hasLocation: location !== null }
      evidence.checks.push(record)
      assert.equal(response.status, expected)
      if (expected === 302) {
        assert.equal(location, link.originalUrl)
        assert.equal(response.headers.get('cache-control'), 'no-store')
        assert.equal(await response.text(), '')
        record.locationMatches = true
      } else {
        assert.equal(location, null)
        const json = await response.json()
        record.code = json.code
        assert.equal(json.code, expected === 404 ? 'SHORT_LINK_NOT_FOUND' : 'SHORT_LINK_EXPIRED')
      }
      console.log(`PASS: real backend ${expected} and response contract`)
    }
    async function openAndConfirm(row, link, status) {
      const label = status === 302 ? '已看到浏览器进入目标站或其登录页' : `已看到浏览器显示 ${status} 对应错误`
      const text = status === 302
        ? '已核对后端302和Location。现在通过桌面按钮打开真实默认浏览器。请查看是否离开本地短地址、进入微博或其登录流程，再切回此窗口确认。如失败，请在聊天说明，不要点击确认。'
        : `已核对后端${status}及错误码。现在通过桌面按钮打开真实浏览器，请确认本地短地址显示对应错误，再切回确认。若不符合，请在聊天说明。`
      await instance.evaluate((_electron, code) => { globalThis.__shortLinkLive.nextOpen = code }, link.shortCode)
      await row.getByRole('button', { name: /^打开 .* 的短地址$/ }).click()
      await expect.poll(() => instance.evaluate(() => globalThis.__shortLinkLive.opens.at(-1)?.handedOff)).toBe(true)
      await panel(text, label)
      console.log(`WAITING: real browser opened for ${status}; inspect it, return to isolated Nexus and confirm in the bottom panel.`)
      await waitConfirmation()
      evidence.humanConfirmed.push(status)
    }
    evidence.stage = '302'
    await checkHttp(active, 302)
    await openAndConfirm(activeRow, active, 302)
    evidence.stage = 'disable-created-link'
    await activeRow.getByRole('button', { name: /^禁用 / }).click()
    await page.getByRole('button', { name: '确认禁用', exact: true }).click()
    await expect(activeRow).toContainText('已禁用')
    evidence.stage = '404'
    await checkHttp(active, 404)
    await openAndConfirm(activeRow, active, 404)
    evidence.stage = 'wait-expiry'
    const delay = Math.max(0, new Date(expiring.expiresAt).getTime() - Date.now() + 1500)
    if (delay > 15000) throw Error('Unexpected expiry interval')
    if (delay) await new Promise(resolve => setTimeout(resolve, delay))
    evidence.stage = '410'
    await checkHttp(expiring, 410)
    await openAndConfirm(expiredRow, expiring, 410)
    const control = await instance.evaluate(() => globalThis.__shortLinkLive)
    assert.equal(control.createAttempts, 2); assert.equal(control.links.length, 2)
    assert.equal(control.opens.length, 3); assert.equal(control.blocked, 0)
    assert.deepEqual(evidence.humanConfirmed, [302, 404, 410])
    assert.match(page.url(), /^nexus:\/\/app\/short-links/)
    evidence.stage = 'passed'; evidence.passed = true
    console.log('PASS: real 302/404/410 contracts and user-confirmed system-browser behavior; two links, three opens, no screenshot requests')
    await panel('真实短链验收通过：302/404/410及浏览器表现均已确认。两条验收记录保留（禁用/已过期）。隔离窗口30秒后关闭；外部浏览器标签页由你自行关闭。', '验收已完成')
    await new Promise(resolve => setTimeout(resolve, 30000))
  } catch (error) {
    evidence.errorType = error.name
    evidence.errorSummary = error.message.split('\n')[0]
    console.error(`FAIL at ${evidence.stage}; no automatic retries or cleanup mutations. Inspect evidence and current window state.`)
    process.exitCode = 1
  } finally {
    if (instance) {
      try { evidence.control = await instance.evaluate(() => globalThis.__shortLinkLive) } catch {}
      await instance.close().catch(() => {})
    }
    await fs.writeFile(path.join(dir, 'evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(`Evidence: ${dir}`)
  }
})().catch(() => { console.error('Short-link live setup failed'); process.exitCode = 1 })
