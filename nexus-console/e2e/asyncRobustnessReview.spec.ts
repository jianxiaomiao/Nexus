import { expect, test } from '@playwright/test'
import { installMockApi, sampleApplication, sampleKey, signIn, openApplication } from './support/mockApi'

function barrier() { let release!: () => void; const promise = new Promise<void>(done => { release = done }); return { promise, release } }

test('application edit retains the version from opening the form after the list refreshes', async ({ page }) => {
  const api = await installMockApi(page); await signIn(page)
  await page.getByRole('button', { name: `更多操作：${sampleApplication.name}`, exact: true }).first().click()
  await page.getByText('编辑名称', { exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑 Application', exact: true })
  await dialog.getByRole('textbox').fill('我的修改')
  api.applications[0]!.updatedAt = '2026-11-19T00:00:00.010Z'
  // A background refresh changes the list while the form retains what the user read.
  await page.evaluate(async () => {
    const path = '/src/api/applications.ts'; const m = await import(path)
    const latest = await m.listApplications()
    const app = document.querySelector('.applications-page') as Element & { __vueParentComponent?: { setupState: { applications: unknown } } }
    if (!app.__vueParentComponent) throw new Error('page state unavailable')
    app.__vueParentComponent.setupState.applications = latest.records
  })
  await dialog.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect.poll(() => api.calls.filter(c => c.method === 'PUT').length).toBe(1)
  expect(api.calls.find(c => c.method === 'PUT')!.body.expectedUpdatedAt).toBe(sampleApplication.updatedAt)
})

for (const key of [false, true]) {
  test(`malformed management update cannot erase the current detail; key=${key}`, async ({ page }) => {
    await installMockApi(page); await openApplication(page)
    if (key) await page.evaluate(async () => {
      const path = '/src/router/index.ts'; const { default: router } = await import(path)
      await router.push('/applications/1/keys/11')
    })
    const name = key ? sampleKey.name : sampleApplication.name
    await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
    await page.route(key ? '**/api/apiKey' : '**/api/application', async route => {
      if (route.request().method() !== 'PUT') return route.fallback()
      await route.fulfill({ json: { code: 'SUCCESS', data: null } })
    })
    await page.getByRole('button', { name: key ? '编辑 Key 名称' : '编辑应用名称', exact: true }).click()
    await page.locator('.el-message-box input').fill('异常响应')
    await page.getByRole('button', { name: '保存修改', exact: true }).click()
    await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: /刷新/ }).first()).toBeVisible()
    await expect(page.locator('body')).toContainText('核对')
  })
}

test('renaming an application releases an invalidated quota refresh and ignores its late failure', async ({ page }) => {
  await installMockApi(page); await openApplication(page)
  const entered = barrier(), finish = barrier()
  await page.route('**/api/application/1/quota', async route => {
    entered.release(); await finish.promise
    await route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', data: null } })
  })
  const refresh = page.getByRole('button', { name: '刷新配额', exact: true })
  await refresh.click(); await entered.promise
  await expect(refresh).toBeDisabled()
  await page.getByRole('button', { name: '编辑应用名称', exact: true }).click()
  await page.locator('.el-message-box input').fill('改名完成')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('heading', { name: '改名完成', exact: true })).toBeVisible()
  await expect(refresh).toBeEnabled()
  const done = page.waitForResponse(r => r.url().endsWith('/api/application/1/quota'))
  finish.release(); await done
  await expect(refresh).toBeEnabled()
  await expect(page.locator('.quota-card')).not.toContainText('加载配额失败')
})

for (const backToA of [false, true]) {
  test(`short-link selection releases operation lock and rejects late A write; returnToA=${backToA}`, async ({ page }) => {
    const first = { id: 31, apiKeyId: 11, name: '短链 A', originalUrl: 'https://weibo.com/1', shortCode: 'testA', status: 0 as const, expiresAt: '2035-01-01T00:00:00Z', createdAt: sampleKey.createdAt, updatedAt: sampleKey.updatedAt }
    const second = { ...first, id: 32, apiKeyId: 12, name: '短链 B', shortCode: 'testB' }
    await installMockApi(page, { applications: [sampleApplication, { ...sampleApplication, id: 2, name: '应用 B' }],
      keys: [sampleKey, { ...sampleKey, id: 12, applicationId: 2 }], shortLinks: [first, second] })
    await signIn(page)
    const visit = (id: number, key: number) => page.evaluate(async ({id,key}) => {
      const path = '/src/router/index.ts'; const { default: router } = await import(path)
      await router.push(`/short-links?applicationId=${id}&apiKeyId=${key}`)
    }, { id, key })
    await visit(1, 11)
    const entered = barrier(), finish = barrier()
    await page.route('**/api/short-links', async route => {
      if (route.request().method() !== 'PUT') return route.fallback()
      entered.release(); await finish.promise
      await route.fulfill({ json: { code: 'SUCCESS', data: { ...first, name: '旧写入结果' } } })
    })
    await page.getByRole('button', { name: '改名 短链 A', exact: true }).click()
    await page.locator('.el-message-box input').fill('旧写入结果')
    await page.getByRole('button', { name: '保存修改', exact: true }).click()
    await entered.promise
    await visit(2, 12)
    await expect(page.getByRole('button', { name: '改名 短链 B', exact: true })).toBeEnabled()
    if (backToA) { await visit(1, 11); await expect(page.getByRole('button', { name: '改名 短链 A', exact: true })).toBeEnabled() }
    const done = page.waitForResponse(r => r.request().method() === 'PUT')
    finish.release(); await done
    await expect(page.getByRole('button', { name: backToA ? '改名 短链 A' : '改名 短链 B', exact: true })).toBeEnabled()
    await expect(page.locator('.short-links-table')).not.toContainText('旧写入结果')
  })
}

for (const sameTarget of [true, false]) {
  test(`screenshot journal serializes tabs without losing markers; sameTarget=${sameTarget}`, async ({ page, context }) => {
    const other = await context.newPage()
    for (const tab of [page, other]) { await installMockApi(tab); await tab.goto('/login') }
    await page.evaluate(async () => {
      const w = window as unknown as { releaseJournal: () => void }
      void navigator.locks.request('nexus.screenshot.submissions.v1', async () => {
        await new Promise<void>(resolve => { w.releaseJournal = resolve })
      })
    })
    await page.waitForFunction(() => typeof (window as unknown as { releaseJournal?: unknown }).releaseJournal === 'function')
    const a = page.evaluate(async () => {
      const path = '/src/api/screenshotSubmission.ts'; const m = await import(path)
      return (await m.prepareScreenshotSubmission('nxk_v1_123e4567-e89b-42d3-a456-426614174000_fixture', 'https://blog.csdn.net/a')).key as string
    })
    const b = other.evaluate(async same => {
      const path = '/src/api/screenshotSubmission.ts'; const m = await import(path)
      return (await m.prepareScreenshotSubmission('nxk_v1_123e4567-e89b-42d3-a456-426614174000_fixture', same ? 'https://blog.csdn.net/a' : 'https://blog.csdn.net/b')).key as string
    }, sameTarget)
    await expect.poll(() => page.evaluate(async () => (await navigator.locks.query()).pending?.filter(l => l.name === 'nexus.screenshot.submissions.v1').length)).toBe(2)
    await page.evaluate(() => (window as unknown as { releaseJournal: () => void }).releaseJournal())
    const [one,two] = await Promise.all([a,b])
    expect(one === two).toBe(sameTarget)
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('nexus.screenshot.submissions.v1')!).length)).toBe(sameTarget ? 1 : 2)
    await other.close()
  })
}

for (const kind of ['5xx', 'malformed', 'switch'] as const) {
  test(`extract write requires reconciliation after ${kind}`, async ({ page }) => {
    await installMockApi(page); await signIn(page)
    await page.getByRole('link', { name: 'API 调试台', exact: true }).click()
    await page.getByRole('button', { name: '网页正文提取', exact: true }).click()
    await page.getByLabel('API Key', { exact: true }).fill('fixture-key')
    await page.getByLabel('文章 URL', { exact: true }).fill('https://blog.csdn.net/article')
    const entered = barrier(), finish = barrier()
    let calls = 0
    await page.route('**/v1/web/extract', async route => {
      calls++; entered.release()
      if (kind === 'switch') await finish.promise
      await route.fulfill({ status: kind === '5xx' ? 503 : 200, json: { code: 'SUCCESS', data: null } }).catch(() => {})
    })
    await page.getByRole('button', { name: '发送请求', exact: true }).click()
    await entered.promise
    if (kind === 'switch') {
      await page.getByRole('button', { name: '生成 UUID', exact: true }).click()
      finish.release()
      await page.getByRole('button', { name: '网页正文提取', exact: true }).click()
    }
    await expect(page.getByRole('button', { name: '核对后开始新操作', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '发送请求', exact: true }).click()
    await expect(page.locator('.playground-form-error')).toContainText('未确认')
    expect(calls).toBe(1)
  })
}
