import { expect, test, type Page, type Route } from '@playwright/test'
import type { AuthState, EnsureAuthSessionInput, EnsureAuthSessionResult, ClearAuthSessionInput, LogoutAuthSessionResult } from '../src/types/desktop'
import { installMockApi, signIn } from './support/mockApi'

type Mode = 'success' | 'failure' | 'lost-ipc' | 'deferred'
type Probe = { rejected: boolean; status?: number }
type TestWindow = Window & {
  __renew: (input: EnsureAuthSessionInput) => Promise<EnsureAuthSessionResult>
  __read: () => Promise<{ status: 'ok'; authState: AuthState }>
  __login: (input: { expectedRevision: number }) => Promise<unknown>
  __logout: (input: ClearAuthSessionInput) => Promise<LogoutAuthSessionResult>
  __probes: Probe[]
}
const probePath = '/api/application/session-probe'
const session = () => ({ accessToken: 'playwright-only-token', tokenType: 'Bearer' as const,
  expiresAt: Date.now() + 300_000, sessionId: '7', sessionExpiresAt: Date.now() + 86_400_000 })
async function setup(page: Page, mode: Mode = 'success') {
  let state: AuthState = { revision: 0, session: null }
  let renewals = 0, logouts = 0, release: (() => void) | undefined
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.name))
  page.on('close', () => release?.())
  await page.exposeFunction('__read', () => ({ status: 'ok', authState: structuredClone(state) }))
  await page.exposeFunction('__login', () => {
    state = { revision: state.revision + 1, session: session() }
    return { status: 'ok', code: 'SUCCESS', authState: structuredClone(state) }
  })
  await page.exposeFunction('__renew', async (input: EnsureAuthSessionInput): Promise<EnsureAuthSessionResult> => {
    renewals++
    expect(input.expectedRevision).toBe(1)
    if (mode === 'lost-ipc') throw Error('Controlled IPC loss')
    if (mode === 'deferred') await new Promise<void>(resolve => { release = resolve })
    if (state.revision !== input.expectedRevision) return { status: 'failed', code: 'SESSION_CHANGED' }
    if (mode === 'failure') {
      state = { revision: state.revision + 1, session: null }
      return { status: 'reauthenticate', code: 'INVALID_REFRESH_TOKEN', authState: structuredClone(state) }
    }
    state = { revision: state.revision + 1, session: { ...state.session!, expiresAt: Date.now() + 300_000 } }
    return { status: 'ok', authState: structuredClone(state) }
  })
  await page.exposeFunction('__logout', (): LogoutAuthSessionResult => {
    logouts++
    state = { revision: state.revision + 1, session: null }
    return { status: 'ok', authState: structuredClone(state), serverRevocation: 'unconfirmed' }
  })
  await page.addInitScript(() => {
    const w = window as unknown as TestWindow
    const unused = async (): Promise<never> => { throw Error('Unexpected desktop operation') }
    w.__probes = []
    window.nexusDesktop = {
      readAuthSession: () => w.__read(),
      loginAuthSession: async input => await w.__login(input) as Awaited<ReturnType<NonNullable<Window['nexusDesktop']>['loginAuthSession']>>,
      ensureAuthSession: input => w.__renew(input), logoutAuthSession: input => w.__logout(input),
      clearAuthSession: unused, saveScreenshot: unused, startScreenshotTracking: unused,
      stopScreenshotTracking: unused, getScreenshotResult: unused,
      readScreenshotTracking: async () => ({ status: 'ok', snapshot: { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null } }),
      onScreenshotTrackingUpdated: () => () => {},
    }
  })
  await installMockApi(page)
  await signIn(page)
  return { state: () => state, renewals: () => renewals, logouts: () => logouts, errors,
    release: () => { if (!release) throw Error('No pending renewal'); release() } }
}
async function expire(page: Page) {
  await page.evaluate(async () => {
    const path = '/src/stores/userStore.ts'
    const { useUserStore } = await import(path)
    useUserStore().expiresAt = Date.now() - 1
  })
}
async function probe(page: Page, count = 1, options: { noBearer?: boolean; noContext?: boolean } = {}) {
  await page.evaluate(({ count, options }) => {
    const path = '/src/api/http.ts'
    void import(path).then(async ({ http }) => {
      if (options.noBearer) {
        const storePath = '/src/stores/userStore.ts'; const { useUserStore } = await import(storePath)
        useUserStore().accessToken = ''
      }
      if (options.noContext) {
        const axiosPath = '/node_modules/.vite/deps/axios.js'; const { default: axios } = await import(axiosPath)
        const adapter = http.defaults.adapter
        http.defaults.adapter = async (config: { authContext?: unknown }) => {
          delete config.authContext
          return axios.getAdapter(adapter)(config)
        }
      }
      for (let index = 0; index < count; index++) {
        void http.get('/application/session-probe').then(
          () => (window as unknown as TestWindow).__probes.push({ rejected: false }),
          (error: { response?: { status: number } }) => (window as unknown as TestWindow).__probes.push({ rejected: true, status: error.response?.status }),
        )
      }
    })
  }, { count, options })
}
async function reject401(route: Route) {
  await route.fulfill({ status: 401, json: { code: 'INVALID_ACCESS_TOKEN' } })
}

for (const trigger of ['request', 'router', 'page-reload'] as const) {
  test(`${trigger}发现AT过期先续期，公开快照更新且不跳登录`, async ({ page }) => {
    const c = await setup(page)
    let requests = 0
    await page.route(`**${probePath}`, async route => { requests++; await route.fulfill({ json: { code: 'SUCCESS', data: null } }) })
    await expire(page)
    if (trigger === 'request') await probe(page)
    else if (trigger === 'page-reload') {
      c.state().session!.expiresAt = Date.now() - 1
      await page.reload()
    } else await page.getByRole('link', { name: '开发文档', exact: true }).click()
    await expect.poll(c.renewals).toBe(1)
    await expect.poll(() => page.evaluate(async () => {
      const path = '/src/stores/userStore.ts'; const { useUserStore } = await import(path)
      return useUserStore().mainRevision
    })).toBe(2)
    await expect(page).not.toHaveURL(/\/login$/)
    expect(c.logouts()).toBe(0)
    if (trigger === 'request') expect(requests).toBe(1)
    expect(c.errors).toEqual([])
  })
}

test('并发过期请求共用一次续期，成功后各自只发送一次业务请求', async ({ page }) => {
  const c = await setup(page, 'deferred')
  let requests = 0
  await page.route(`**${probePath}`, async route => { requests++; await route.fulfill({ json: { code: 'SUCCESS' } }) })
  await expire(page)
  await probe(page, 3)
  await expect.poll(c.renewals).toBe(1)
  expect(requests).toBe(0)
  c.release()
  await expect.poll(() => requests).toBe(3)
  expect(c.renewals()).toBe(1)
})

test('当前401尝试续期但原业务请求仍reject，不自动重放', async ({ page }) => {
  const c = await setup(page)
  let requests = 0
  await page.route(`**${probePath}`, async route => { requests++; await reject401(route) })
  await probe(page)
  await expect.poll(c.renewals).toBe(1)
  await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__probes.length)).toBe(1)
  expect(await page.evaluate(() => (window as unknown as TestWindow).__probes[0])).toEqual({ rejected: true, status: 401 })
  expect(requests).toBe(1)
  expect(c.state().revision).toBe(2)
  await expect(page).toHaveURL(/\/applications$/)
})

for (const mode of ['failure', 'lost-ipc'] as const) {
  test(`续期${mode}后本地退出并提示，刷新页面不恢复`, async ({ page }) => {
    const c = await setup(page, mode)
    await page.route(`**${probePath}`, reject401)
    await probe(page)
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.locator('.form-wrap .el-alert')).toContainText(/续期|失效/)
    expect(c.state().session).toBeNull()
    expect(c.renewals()).toBe(1)
    expect(c.logouts()).toBe(mode === 'lost-ipc' ? 1 : 0)
    await page.reload()
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
    expect(c.state().session).toBeNull()
  })
}

for (const saved of [false, true]) {
  test(`新登录意图${saved ? '已保存' : '未保存'}时，旧401不触发续期或清理`, async ({ page }) => {
    const c = await setup(page)
    let pending: Route | undefined
    await page.route(`**${probePath}`, route => { pending = route })
    await probe(page)
    await expect.poll(() => !!pending).toBe(true)
    await page.evaluate(async saved => {
      const path = '/src/stores/userStore.ts'; const { useUserStore } = await import(path)
      const store = useUserStore(); const attempt = store.beginLogin()
      if (saved) {
        const authPath = '/src/api/auth.ts'; const { login } = await import(authPath)
        const result = await login({ email: 'fixture@example.test', password: 'fixture-only' }, attempt.mainRevision)
        await store.completeLogin(attempt, result.data)
      }
    }, saved)
    await reject401(pending!)
    await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__probes.length)).toBe(1)
    expect(c.renewals()).toBe(0)
    expect(c.logouts()).toBe(0)
    expect(c.state().revision).toBe(saved ? 2 : 1)
  })
}

for (const [status, code] of [[401, 'OTHER_UNAUTHORIZED'], [403, 'INVALID_ACCESS_TOKEN'], [500, 'INTERNAL_ERROR']] as const) {
  test(`${status}/${code}不续期也不清理`, async ({ page }) => {
    const c = await setup(page)
    await page.route(`**${probePath}`, route => route.fulfill({ status, json: { code } }))
    await probe(page)
    await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__probes.length)).toBe(1)
    expect(c.renewals()).toBe(0)
    expect(c.logouts()).toBe(0)
  })
}

test('退出开始后迟到的续期结果不能恢复页面会话', async ({ page }) => {
  const c = await setup(page, 'deferred')
  await expire(page)
  await probe(page)
  await expect.poll(c.renewals).toBe(1)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  c.release()
  await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__probes.length)).toBe(1)
  await expect(page).toHaveURL(/\/login$/)
  expect(c.logouts()).toBe(1)
})

for (const option of ['noBearer', 'noContext'] as const) {
  test(`缺少${option}的401不猜测续期目标`, async ({ page }) => {
    const c = await setup(page)
    await page.route(`**${probePath}`, reject401)
    await probe(page, 1, { [option]: true })
    await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__probes.length)).toBe(1)
    expect(c.renewals()).toBe(0)
    expect(c.logouts()).toBe(0)
  })
}

test('网络失败不触发续期或退出', async ({ page }) => {
  const c = await setup(page)
  await page.route(`**${probePath}`, route => route.abort('failed'))
  await probe(page)
  await expect.poll(() => page.evaluate(() => (window as unknown as TestWindow).__probes.length)).toBe(1)
  expect(c.renewals()).toBe(0)
  expect(c.logouts()).toBe(0)
})

test('失效后导航被阻止时保持凭据为空并提示导航失败', async ({ page }) => {
  const c = await setup(page, 'failure')
  await page.evaluate(async () => {
    const path = '/src/router/index.ts'; const { default: router } = await import(path)
    router.beforeEach((to: { name: string }) => to.name !== 'login')
  })
  await page.route(`**${probePath}`, reject401)
  await probe(page)
  await expect(page.getByText('会话已清理，但页面切换失败，请刷新页面。', { exact: true })).toBeVisible()
  expect(c.state().session).toBeNull()
  await expect(page).toHaveURL(/\/applications$/)
})
