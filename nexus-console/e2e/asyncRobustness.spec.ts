import { expect, test } from '@playwright/test'
import { installMockApi, sampleApplication, sampleKey, signIn, openApplication } from './support/mockApi'

function gate() { let release!: () => void; const promise = new Promise<void>(resolve => { release = resolve }); return { promise, release } }

for (const oldFails of [false, true]) {
  test(`list ignores late ${oldFails ? 'failure' : 'success'} including error and loading`, async ({ page }) => {
    await installMockApi(page, { applications: Array.from({ length: 11 }, (_, i) => ({ ...sampleApplication, id: i + 1, name: `初始-${i}` })) })
    await signIn(page)
    const first = gate(), received = gate()
    await page.route('**/api/application?**', async route => {
      const size = new URL(route.request().url()).searchParams.get('size')
      if (size === '5') {
        received.release(); await first.promise
        await route.fulfill({ status: oldFails ? 503 : 200, json: oldFails ? { code: 'TEMPORARY_ERROR', data: null } : { code: 'SUCCESS', data: { records: [{ ...sampleApplication, name: '过期结果' }], total: 11, current: 1, size: 5 } } })
      } else await route.fulfill({ json: { code: 'SUCCESS', data: { records: [{ ...sampleApplication, name: '最新结果' }], total: 11, current: 1, size: 3 } } })
    })
    const size = page.getByRole('spinbutton', { name: '每页条数' })
    await size.fill('5'); await size.blur(); await received.promise
    await size.fill('3'); await size.blur()
    await expect(page.getByRole('heading', { name: '最新结果' })).toBeVisible()
    const finished = page.waitForResponse(response => response.url().includes('/api/application?') && response.url().includes('size=5'))
    first.release(); await finished
    await expect(page.getByRole('heading', { name: '最新结果' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '过期结果' })).toHaveCount(0)
    await expect(page.getByText('暂时无法加载应用', { exact: true })).toHaveCount(0)
  })
}

test('late mutation for A cannot replace the reused detail for B', async ({ page }) => {
  await installMockApi(page, { applications: [sampleApplication, { ...sampleApplication, id: 2, name: '应用 B' }] })
  await openApplication(page)
  const write = gate(), received = gate()
  await page.route('**/api/application', async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    received.release(); await write.promise
    await route.fulfill({ json: { code: 'SUCCESS', data: { ...sampleApplication, name: 'A 修改完成' } } })
  })
  await page.getByRole('button', { name: '编辑应用名称' }).click()
  await page.locator('.el-message-box input').fill('A 修改完成')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await received.promise
  await page.evaluate(async () => {
    const path = '/src/router/index.ts'; const { default: router } = await import(path)
    await router.push('/applications/2')
  })
  await expect(page.getByRole('heading', { name: '应用 B' })).toBeVisible()
  const finished = page.waitForResponse(response => response.request().method() === 'PUT')
  write.release(); await finished
  await expect(page.getByRole('heading', { name: '应用 B' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'A 修改完成' })).toHaveCount(0)
})

test('logout clears an unsaved one-time Key and cannot be blocked by its guard', async ({ page }) => {
  await installMockApi(page)
  await openApplication(page)
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await page.getByRole('button', { name: '创建 API Key', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '创建 API Key', exact: true })
  await dialog.getByRole('textbox').fill('临时 Key')
  await dialog.getByRole('button', { name: '创建 Key', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'API Key 创建成功' })).toBeVisible()
  // A cross-tab/logout intent is allowed to arrive while this modal owns focus.
  await page.evaluate(async () => {
    const p = '/src/stores/userStore.ts'; const { useUserStore } = await import(p)
    await useUserStore().invalidateSession({ reason: 'logout' })
    const r = '/src/router/index.ts'; const { default: router } = await import(r)
    await router.replace({ name: 'login' })
  })
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('dialog', { name: 'API Key 创建成功' })).toHaveCount(0)
})

test('old finally cannot stop the newer loading state', async ({ page }) => {
  await installMockApi(page, { applications: Array.from({ length: 11 }, (_, i) => ({ ...sampleApplication, id: i + 1 })) })
  await signIn(page)
  const a = gate(), b = gate(), enteredA = gate(), enteredB = gate()
  await page.route('**/api/application?**', async route => {
    if (new URL(route.request().url()).searchParams.get('size') === '5') {
      enteredA.release(); await a.promise
      await route.fulfill({ status: 503, json: { code: 'TEMPORARY_ERROR', data: null } })
    } else {
      enteredB.release(); await b.promise
      await route.fulfill({ json: { code: 'SUCCESS', data: { records: [sampleApplication], total: 11, current: 1, size: 3 } } })
    }
  })
  const size = page.getByRole('spinbutton', { name: '每页条数' })
  await size.fill('5'); await size.blur(); await enteredA.promise
  await size.fill('3'); await size.blur(); await enteredB.promise
  const oldFinished = page.waitForResponse(response => response.url().includes('size=5'))
  a.release(); await oldFinished
  await expect(page.locator('.skeleton-card')).toHaveCount(3)
  await expect(page.getByText('暂时无法加载应用', { exact: true })).toHaveCount(0)
  b.release()
  await expect(page.getByRole('heading', { name: sampleApplication.name })).toBeVisible()
})

test('remote deletion to zero normalizes an out-of-range page', async ({ page }) => {
  const api = await installMockApi(page, { applications: Array.from({ length: 11 }, (_, i) => ({ ...sampleApplication, id: i + 1 })) })
  await signIn(page)
  await expect(page.locator('.application-card:not(.skeleton-card)')).toHaveCount(10)
  api.applications.splice(0)
  await page.locator('.list-pagination .btn-next').click()
  await expect(page.getByRole('heading', { name: '从第一个 Application 开始' })).toBeVisible()
  expect(api.calls.filter(call => call.path === '/api/application').slice(-2).map(call => call.query.current)).toEqual(['2', '1'])
})

test('management transport rejects an old success after a new login intent', async ({ page }) => {
  await installMockApi(page)
  await signIn(page)
  const response = gate(), received = gate()
  await page.route('**/api/application?applicationId=1', async route => {
    received.release(); await response.promise
    await route.fulfill({ json: { code: 'SUCCESS', data: { records: [sampleApplication], total: 1, current: 1, size: 10 } } })
  })
  const old = page.evaluate(async () => {
    const path = '/src/api/http.ts'; const { http } = await import(path)
    try { await http.get('/application', { params: { applicationId: 1 } }); return 'accepted' }
    catch (error) { return (error as { code?: string }).code }
  })
  await received.promise
  await page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'; const { useUserStore } = await import(path)
    useUserStore().beginLogin()
  })
  response.release()
  expect(await old).toBe('ERR_CANCELED')
})

test('forced logout also dismisses a pending global edit confirmation without submitting', async ({ page }) => {
  const api = await installMockApi(page)
  await openApplication(page)
  await page.getByRole('button', { name: '编辑应用名称' }).click()
  await expect(page.getByRole('dialog', { name: '编辑应用名称' })).toBeVisible()
  await page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'; const { useUserStore } = await import(path)
    await useUserStore().invalidateSession({ reason: 'logout' })
    const p = '/src/router/index.ts'; const { default: router } = await import(p)
    await router.replace('/login')
  })
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('dialog', { name: '编辑应用名称' })).toHaveCount(0)
  expect(api.calls.filter(call => call.method === 'PUT')).toHaveLength(0)
})

test('history-style query changes reload the short-link resource selection', async ({ page }) => {
  await installMockApi(page, {
    applications: [sampleApplication, { ...sampleApplication, id: 2, name: '应用 B' }],
    keys: [{ ...sampleKey, name: 'Key A' }, { ...sampleKey, id: 12, applicationId: 2, name: 'Key B' }],
  })
  await signIn(page)
  const visit = async (url: string) => page.evaluate(async url => {
    const p = '/src/router/index.ts'; const { default: router } = await import(p)
    await router.push(url)
  }, url)
  await visit('/short-links?applicationId=1&apiKeyId=11')
  await expect(page.getByRole('heading', { name: 'Key A的短链接' })).toBeVisible()
  await visit('/short-links?applicationId=2&apiKeyId=12')
  await expect(page.getByRole('heading', { name: 'Key B的短链接' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { name: 'Key A的短链接' })).toBeVisible()
})

test('a saved but still open rotated Key cannot follow a reused detail to another Key', async ({ page }) => {
  await installMockApi(page, { keys: [sampleKey, { ...sampleKey, id: 12, name: '另一个 Key', publicId: 'other-public' }] })
  await openApplication(page)
  const visit = async (url: string) => page.evaluate(async url => {
    const p = '/src/router/index.ts'; const { default: router } = await import(p)
    await router.push(url)
  }, url)
  await visit('/applications/1/keys/11')
  await page.getByRole('button', { name: '轮换凭据' }).click()
  await page.getByRole('button', { name: '确认轮换', exact: true }).click()
  const reveal = page.getByRole('dialog', { name: '轮换成功' })
  await expect(reveal).toBeVisible()
  await reveal.getByText('我已安全保存新的 API Key').click()
  await visit('/applications/1/keys/12')
  await expect(page.getByRole('heading', { name: '另一个 Key' })).toBeVisible()
  await expect(reveal).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('PLAYWRIGHT_FAKE_ROTATED_KEY_NOT_VALID')
})

for (const throws of [false, true]) {
  test(`successful deletion is not reported as failed when navigation ${throws ? 'throws' : 'is blocked'}`, async ({ page }) => {
    const api = await installMockApi(page)
    await openApplication(page)
    await page.evaluate(async throws => {
      const p = '/src/router/index.ts'; const { default: router } = await import(p)
      router.beforeEach((to: { name?: string }) => {
        if (to.name !== 'applications') return
        if (throws) throw Error('fixture-navigation-failure')
        return false
      })
    }, throws)
    await page.getByRole('button', { name: '删除应用' }).click()
    await page.getByPlaceholder('输入应用名称').fill(sampleApplication.name)
    await page.getByRole('button', { name: '确认删除' }).click()
    await expect(page.getByText('已删除，但页面跳转失败，请返回列表。', { exact: true })).toBeVisible()
    expect(api.calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
    expect(api.applications).toHaveLength(0)
    await expect(page.getByRole('button', { name: '删除应用' })).toHaveCount(0)
  })
}
