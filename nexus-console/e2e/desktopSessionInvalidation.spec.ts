import { expect, test, type Page, type Route } from '@playwright/test'
import type { Router } from 'vue-router'
import type { AuthState, ClearAuthSessionInput, WriteAuthSessionInput } from '../src/types/desktop'
import { installMockApi, signIn } from './support/mockApi'

type ProbeResult = { rejected: boolean; canceled: boolean; status?: number; hasContext: boolean }
type TestWindow = Window & {
  __probeResults: ProbeResult[]
  __read: () => Promise<unknown>
  __write: (input: WriteAuthSessionInput) => Promise<unknown>
  __clear: (input: ClearAuthSessionInput) => Promise<unknown>
}
type Mode = 'ok' | 'lost-ack' | 'unavailable' | 'conflict' | 'deferred'
const probePath = '/api/application/session-probe'

async function setup(page: Page, mode: Mode = 'ok', desktop = true) {
  let state: AuthState = { revision: 0, session: null }
  let clears = 0, reads = 0
  let releaseClear: (() => void) | undefined
  const errors: string[] = []
  const outside: string[] = []
  page.on('pageerror', error => errors.push(error.name))
  page.on('close', () => releaseClear?.())
  await page.exposeFunction('__read', () => {
    reads++
    return { status: 'ok', authState: structuredClone(state) }
  })
  await page.exposeFunction('__write', (input: WriteAuthSessionInput) => {
    expect(input.expectedRevision).toBe(state.revision)
    state = { revision: state.revision + 1, session: structuredClone(input.session) }
    return { status: 'ok', code: 'SUCCESS', authState: structuredClone(state) }
  })
  await page.exposeFunction('__clear', async (input: ClearAuthSessionInput) => {
    clears++
    expect(input.expectedRevision).toBe(1)
    if (mode === 'unavailable') throw Error('Controlled IPC failure')
    if (mode === 'conflict') {
      state = { revision: 2, session: {
        accessToken: 'fixture-other-main-session', tokenType: 'Bearer', expiresAt: Date.now() + 300_000,
      } }
      return { status: 'failed', code: 'SESSION_CHANGED' }
    }
    if (mode === 'deferred') await new Promise<void>(resolve => { releaseClear = resolve })
    state = { revision: state.revision + 1, session: null }
    if (mode === 'lost-ack') throw Error('Controlled lost acknowledgement')
    return { status: 'ok', code: 'SUCCESS', authState: structuredClone(state) }
  })
  await page.addInitScript(({ desktop }) => {
    const w = window as unknown as TestWindow
    w.__probeResults = []
    if (!desktop) return
    const unused = async (): Promise<never> => { throw Error('Unexpected desktop business operation') }
    // Only the auth bridge is simulated. The Store, Router and Axios run unchanged.
    w.nexusDesktop = {
      readAuthSession: async () => await w.__read() as Awaited<ReturnType<NonNullable<Window['nexusDesktop']>['readAuthSession']>>,
      writeAuthSession: async input => await w.__write(input) as Awaited<ReturnType<NonNullable<Window['nexusDesktop']>['writeAuthSession']>>,
      clearAuthSession: async input => await w.__clear(input) as Awaited<ReturnType<NonNullable<Window['nexusDesktop']>['clearAuthSession']>>,
      openShortLink: unused, saveScreenshot: unused, startScreenshotTracking: unused,
      stopScreenshotTracking: unused, getScreenshotResult: unused,
      readScreenshotTracking: async () => ({ status: 'ok', snapshot: {
        jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null,
      } }),
      onScreenshotTrackingUpdated: () => () => {},
    }
  }, { desktop })
  await installMockApi(page)
  // No machine endpoint may reach a real backend, including screenshot creation.
  await page.route('**/v1/**', async route => {
    outside.push(route.request().method())
    await route.abort()
  })
  await signIn(page)
  await expect(page.getByRole('article').filter({ hasText: '示例应用' })).toBeVisible()
  return {
    state: () => structuredClone(state), clears: () => clears, reads: () => reads, errors, outside,
    release: () => { if (!releaseClear) throw Error('No pending clear'); releaseClear() },
  }
}

async function startProbe(page: Page, options: { noBearer?: boolean; noContext?: boolean } = {}) {
  await page.evaluate(async options => {
    const path = '/src/api/http.ts'
    const { http } = await import(path)
    // Remove metadata only after the real request interceptor, to verify missing-context handling.
    if (options.noContext) {
      const original = http.defaults.adapter
      const axiosPath = '/node_modules/.vite/deps/axios.js'
      const { default: axios } = await import(axiosPath)
      http.defaults.adapter = async (config: { authContext?: unknown }) => {
        delete config.authContext
        return axios.getAdapter(original)(config)
      }
    }
    if (options.noBearer) {
      const storePath = '/src/stores/userStore.ts'
      const { useUserStore } = await import(storePath)
      useUserStore().clearSession()
    }
    void http.get('/application/session-probe').then(() => {
      (window as unknown as TestWindow).__probeResults.push({ rejected: false, canceled: false, hasContext: false })
    }, (error: { code?: string; response?: { status: number }; config?: { authContext?: unknown } }) => {
      (window as unknown as TestWindow).__probeResults.push({
        rejected: true, canceled: error.code === 'ERR_CANCELED', status: error.response?.status,
        hasContext: !!error.config?.authContext,
      })
    })
  }, options)
}

async function snapshot(page: Page) {
  return page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'
    const { useUserStore } = await import(path)
    const store = useUserStore()
    return { hasSession: !!store.accessToken, revision: store.mainRevision,
      operation: store.operationVersion, clearing: store.clearing, error: store.clearError }
  })
}

async function navigate(page: Page, target = '/docs') {
  await page.evaluate(target => {
    const app = (document.querySelector('#app') as HTMLElement & {
      __vue_app__: { config: { globalProperties: { $router: Router } } }
    }).__vue_app__
    void app.config.globalProperties.$router.push(target)
  }, target)
}

async function expire(page: Page) {
  await page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'
    const { useUserStore } = await import(path)
    useUserStore().expiresAt = Date.now() - 1
  })
}

async function reject401(route: Route) {
  await route.fulfill({ status: 401, json: { code: 'INVALID_ACCESS_TOKEN', message: 'Controlled invalid token' } })
}

for (const mode of ['ok', 'lost-ack'] as const) {
  test(`当前401清理后跳登录，${mode}且刷新不恢复`, async ({ page }) => {
    const control = await setup(page, mode)
    let requests = 0
    await page.route(`**${probePath}`, async route => {
      requests++
      const request = route.request()
      expect(request.headers()['authorization']).toMatch(/^Bearer /)
      expect(request.url()).not.toContain('operationVersion')
      expect(Object.keys(request.headers()).some(key => /revision|context|operationversion/i.test(key))).toBe(false)
      await reject401(route)
    })
    await startProbe(page)
    await expect(page).toHaveURL(/\/login$/)
    await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
    expect(await page.evaluate(() => (window as unknown as TestWindow).__probeResults[0])).toMatchObject({
      rejected: true, canceled: false, status: 401, hasContext: true,
    })
    expect(control.state()).toEqual({ revision: 2, session: null })
    expect(control.clears()).toBe(1)
    expect(control.reads()).toBe(mode === 'lost-ack' ? 2 : 1)
    expect(requests).toBe(1) // No replay.
    await page.reload()
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
    expect(control.clears()).toBe(1)
    expect(control.errors).toEqual([])
    expect(control.outside).toEqual([])
  })
}

for (const newerMain of [false, true]) {
  test(`迟到401不影响新登录意图，Main版本${newerMain ? '已变化' : '尚未变化'}`, async ({ page }) => {
    const control = await setup(page)
    let pending: Route | undefined
    await page.route(`**${probePath}`, route => { pending = route })
    await startProbe(page)
    await expect.poll(() => !!pending).toBe(true)
    const previous = await snapshot(page)
    await page.evaluate(async newerMain => {
      const path = '/src/stores/userStore.ts'
      const { useUserStore } = await import(path)
      const store = useUserStore()
      const attempt = store.beginLogin()
      if (newerMain) await store.completeLogin(attempt, {
        accessToken: 'fixture-new-login', tokenType: 'Bearer', expiresInSeconds: 300,
      })
    }, newerMain)
    const current = await snapshot(page)
    expect(current.operation).toBeGreaterThan(previous.operation)
    expect(current.revision).toBe(newerMain ? 2 : 1)
    await reject401(pending!)
    await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
    expect(await snapshot(page)).toEqual(current)
    expect(control.clears()).toBe(0)
    await expect(page).toHaveURL(/\/applications$/)
    expect(control.errors).toEqual([])
  })
}

test('多个401共用一次Main清理，等待期间阻止请求和页面导航', async ({ page }) => {
  const control = await setup(page, 'deferred')
  const pending: Route[] = []
  await page.route(`**${probePath}`, route => { pending.push(route) })
  await startProbe(page)
  await startProbe(page)
  await expect.poll(() => pending.length).toBe(2)
  await Promise.all(pending.map(reject401))
  await expect.poll(control.clears).toBe(1)
  await startProbe(page)
  await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults)).toEqual([
    { rejected: true, canceled: true, status: undefined, hasContext: false },
  ])
  expect(pending).toHaveLength(2)
  await navigate(page)
  await expect(page).toHaveURL(/\/applications$/)
  control.release()
  await expect(page).toHaveURL(/\/login$/)
  expect(control.clears()).toBe(1)
  expect(control.errors).toEqual([])
  await expect(page.getByText('会话已清理，但页面切换失败，请刷新页面。', { exact: true })).toHaveCount(0)
})

for (const mode of ['unavailable', 'conflict'] as const) {
  test(`401清理${mode}不跳转、不重清，阻止后续请求和受保护导航`, async ({ page }) => {
    const control = await setup(page, mode)
    let requests = 0
    await page.route(`**${probePath}`, async route => { requests++; await reject401(route) })
    await startProbe(page)
    await expect.poll(async () => (await snapshot(page)).clearing).toBe(false)
    await expect.poll(async () => (await snapshot(page)).error).not.toBeNull()
    await expect(page.getByRole('alert', { name: '退出登录状态' })).toBeVisible()
    expect(control.clears()).toBe(1)
    expect(control.reads()).toBe(2)
    expect(control.state().session).not.toBeNull()
    await startProbe(page)
    await navigate(page)
    await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(2)
    expect(requests).toBe(1)
    expect(control.clears()).toBe(1)
    await expect(page).toHaveURL(/\/applications$/)
    expect(control.errors).toEqual([])
  })
}

for (const source of ['router', 'request'] as const) {
  test(`${source}发现过期先清Main，过期请求不发送`, async ({ page }) => {
    const control = await setup(page, 'deferred')
    let requests = 0
    await page.route(`**${probePath}`, async route => { requests++; await reject401(route) })
    await expire(page)
    if (source === 'router') await navigate(page)
    else await startProbe(page)
    await expect.poll(control.clears).toBe(1)
    await expect(page).toHaveURL(/\/applications$/)
    expect(requests).toBe(0)
    control.release()
    await expect(page).toHaveURL(/\/login$/)
    expect(control.state().session).toBeNull()
    expect(control.errors).toEqual([])
    if (source === 'request') {
      await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
      expect(await page.evaluate(() => (window as unknown as TestWindow).__probeResults[0])).toMatchObject({ canceled: true })
    }
  })
}

test('Router等待清理后不使用旧判断覆盖更新的本地会话', async ({ page }) => {
  const control = await setup(page, 'deferred')
  await expire(page)
  await navigate(page)
  await expect.poll(control.clears).toBe(1)
  await page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'
    const { useUserStore } = await import(path)
    useUserStore().setSession({ accessToken: 'fixture-new-local', tokenType: 'Bearer', expiresInSeconds: 300 })
  })
  control.release()
  await expect.poll(async () => (await snapshot(page)).clearing).toBe(false)
  expect((await snapshot(page)).hasSession).toBe(true)
  await expect(page).toHaveURL(/\/applications$/)
})

for (const [status, code] of [[401, 'OTHER_UNAUTHORIZED'], [403, 'INVALID_ACCESS_TOKEN'], [500, 'INTERNAL_ERROR']] as const) {
  test(`${status}/${code}不清管理会话`, async ({ page }) => {
    const control = await setup(page)
    const before = await snapshot(page)
    await page.route(`**${probePath}`, route => route.fulfill({ status, json: { code } }))
    await startProbe(page)
    await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
    expect(await snapshot(page)).toEqual(before)
    expect(control.clears()).toBe(0)
  })
}

for (const option of ['noBearer', 'noContext'] as const) {
  test(`缺少${option}的401不猜测清理目标`, async ({ page }) => {
    const control = await setup(page)
    await page.route(`**${probePath}`, reject401)
    await startProbe(page, { [option]: true })
    await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
    expect(control.clears()).toBe(0)
    expect((await snapshot(page)).error).toBeNull()
    await expect(page).toHaveURL(/\/applications$/)
  })
}

test('Web当前401仍只清本地并进入登录页', async ({ page }) => {
  const control = await setup(page, 'ok', false)
  await page.route(`**${probePath}`, reject401)
  await startProbe(page)
  await expect(page).toHaveURL(/\/login$/)
  expect((await snapshot(page)).hasSession).toBe(false)
  expect(control.clears()).toBe(0)
})

test('网络失败不清管理会话', async ({ page }) => {
  const control = await setup(page)
  const before = await snapshot(page)
  await page.route(`**${probePath}`, route => route.abort('failed'))
  await startProbe(page)
  await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
  expect(await snapshot(page)).toEqual(before)
  expect(control.clears()).toBe(0)
})

for (const source of ['router', 'request'] as const) {
  test(`${source}过期清理失败保持页面并阻止请求`, async ({ page }) => {
    const control = await setup(page, 'unavailable')
    let requests = 0
    await page.route(`**${probePath}`, async route => { requests++; await reject401(route) })
    await expire(page)
    if (source === 'router') await navigate(page)
    else await startProbe(page)
    await expect.poll(async () => (await snapshot(page)).error).not.toBeNull()
    await expect(page).toHaveURL(/\/applications$/)
    expect(requests).toBe(0)
    expect(control.clears()).toBe(1)
    expect(control.state().session).not.toBeNull()
  })
}

test('HTTP清理成功但导航被取消时保留空会话并提示导航失败', async ({ page }) => {
  const control = await setup(page)
  await page.evaluate(() => {
    const root = document.querySelector('#app') as HTMLElement & {
      __vue_app__: { config: { globalProperties: { $router: Router } } }
    }
    root.__vue_app__.config.globalProperties.$router.beforeEach(to => to.name !== 'login')
  })
  await page.route(`**${probePath}`, reject401)
  await startProbe(page)
  await expect(page.getByText('会话已清理，但页面切换失败，请刷新页面。', { exact: true })).toBeVisible()
  expect(control.state().session).toBeNull()
  expect((await snapshot(page)).hasSession).toBe(false)
  await expect(page).toHaveURL(/\/applications$/)
  expect(control.clears()).toBe(1)
})

test('HTTP等待清理期间出现新本地操作时不触发旧跳转', async ({ page }) => {
  const control = await setup(page, 'deferred')
  await page.route(`**${probePath}`, reject401)
  await startProbe(page)
  await expect.poll(control.clears).toBe(1)
  await page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'
    const { useUserStore } = await import(path)
    useUserStore().setSession({ accessToken: 'fixture-later-local-session', tokenType: 'Bearer', expiresInSeconds: 300 })
  })
  control.release()
  await expect.poll(async () => page.evaluate(() => (window as unknown as TestWindow).__probeResults.length)).toBe(1)
  expect((await snapshot(page)).hasSession).toBe(true)
  await expect(page).toHaveURL(/\/applications$/)
  expect(control.clears()).toBe(1)
})
