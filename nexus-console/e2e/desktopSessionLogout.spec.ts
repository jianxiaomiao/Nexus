import { expect, test, type Page } from '@playwright/test'
import type { Router } from 'vue-router'
import type {
  AuthState, ClearAuthSessionInput, ClearAuthSessionResult,
  ReadAuthSessionResult, LoginAuthSessionInput, LoginAuthSessionResult,
} from '../src/types/desktop'
import { installMockApi, signIn } from './support/mockApi'

type BridgeWindow = Window & {
  __logoutRead: () => Promise<ReadAuthSessionResult>
  __logoutWrite: (input: LoginAuthSessionInput) => Promise<LoginAuthSessionResult>
  __logoutClear: (input: ClearAuthSessionInput) => Promise<ClearAuthSessionResult>
}

async function setup(page: Page) {
  // This state lives outside the Renderer so a page reload cannot erase it.
  let state: AuthState = { revision: 0, session: null }
  let clears = 0
  let reads = 0
  let pending: {
    resolve: (result: ClearAuthSessionResult) => void
    reject: (error: Error) => void
  } | null = null
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.name))
  await page.exposeFunction('__logoutRead', (): ReadAuthSessionResult => {
    reads++
    return { status: 'ok', authState: structuredClone(state) }
  })
  await page.exposeFunction('__logoutWrite', (input: LoginAuthSessionInput): LoginAuthSessionResult => {
    expect(input.expectedRevision).toBe(state.revision)
    state = { revision: state.revision + 1, session: { accessToken: 'playwright-only-token', tokenType: 'Bearer', expiresAt: Date.now() + 300_000, sessionId: '1', sessionExpiresAt: Date.now() + 86_400_000 } }
    return { status: 'ok', code: 'SUCCESS', authState: structuredClone(state) }
  })
  await page.exposeFunction('__logoutClear', (input: ClearAuthSessionInput) => {
    clears++
    expect(input.expectedRevision).toBe(state.revision)
    return new Promise<ClearAuthSessionResult>((resolve, reject) => { pending = { resolve, reject } })
  })
  await page.addInitScript(() => {
    const w = window as unknown as BridgeWindow
    const unused = async (): Promise<never> => { throw Error('Unexpected desktop operation') }
    w.nexusDesktop = {
      readAuthSession: () => w.__logoutRead(),
      loginAuthSession: input => w.__logoutWrite(input),
      ensureAuthSession: unused,
      logoutAuthSession: async input => {
        const result = await w.__logoutClear(input)
        return result.status === 'ok' ? { ...result, serverRevocation: 'confirmed' as const } : result
      },
      clearAuthSession: input => w.__logoutClear(input),
      openShortLink: unused,
      saveScreenshot: unused,
      startScreenshotTracking: unused,
      stopScreenshotTracking: unused,
      getScreenshotResult: unused,
      readScreenshotTracking: async () => ({ status: 'ok', snapshot: { jobId: null, latestJob: null, traceStatus: 'IDLE', queryFailedInfo: null } }),
      onScreenshotTrackingUpdated: () => () => {},
    }
  })
  await installMockApi(page)
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Applications', exact: true })).toBeVisible()
  return {
    clears: () => clears,
    reads: () => reads,
    state: () => structuredClone(state),
    pageErrors,
    success() {
      if (!pending) throw Error('No pending clear')
      state = { revision: state.revision + 1, session: null }
      pending.resolve({ status: 'ok', code: 'SUCCESS', authState: structuredClone(state) })
      pending = null
    },
    failure(kind: 'forbidden' | 'rejection') {
      if (!pending) throw Error('No pending clear')
      if (kind === 'rejection') pending.reject(Error('Controlled IPC rejection'))
      else pending.resolve({ status: 'failed', code: 'FORBIDDEN' })
      pending = null
    },
    newerSession() {
      if (!pending) throw Error('No pending clear')
      state = { revision: state.revision + 1, session: {
        accessToken: 'fixture-newer-main-token', tokenType: 'Bearer', expiresAt: Date.now() + 300_000, sessionId: '2', sessionExpiresAt: Date.now() + 86_400_000,
      } }
      pending.resolve({ status: 'failed', code: 'SESSION_CHANGED' })
      pending = null
    },
  }
}

test('退出等待Main确认、禁止重复点击，成功后跳转且刷新不恢复', async ({ page }) => {
  const control = await setup(page)
  const button = page.getByRole('button', { name: '退出登录', exact: true })
  await button.click()
  await expect.poll(control.clears).toBe(1)
  await expect(button).toBeDisabled()
  await expect(page).toHaveURL(/\/applications$/)
  await button.dispatchEvent('click')
  expect(control.clears()).toBe(1)
  expect(control.state().session).not.toBeNull()
  control.success()
  await expect(page).toHaveURL(/\/login$/)
  expect(control.state()).toEqual({ revision: 2, session: null })
  await page.reload()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)
  expect(control.clears()).toBe(1)
  expect(control.pageErrors).toEqual([])
})

for (const failure of ['forbidden', 'rejection'] as const) {
  test(`清理${failure}留在当前页并提示，重试确认后才跳转`, async ({ page }) => {
    const control = await setup(page)
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expect.poll(control.clears).toBe(1)
    control.failure(failure)
    await expect(page.getByRole('alert', { name: '退出登录状态' })).toContainText('尚未确认退出，请重试清理会话。')
    await expect(page).toHaveURL(/\/applications$/)
    expect(control.state().session).not.toBeNull()
    const retry = page.getByRole('button', { name: '重试退出', exact: true })
    await expect(retry).toBeEnabled()
    await retry.click()
    await expect.poll(control.clears).toBe(2)
    await expect(page.locator('.logout-button')).toBeDisabled()
    control.success()
    await expect(page).toHaveURL(/\/login$/)
    expect(control.pageErrors).toEqual([])
  })
}

test('Main已有较新会话时显示冲突，不自动再清或跳转', async ({ page }) => {
  const control = await setup(page)
  const readsBefore = control.reads()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect.poll(control.clears).toBe(1)
  control.newerSession()
  await expect(page.getByRole('alert', { name: '退出登录状态' })).toContainText('会话已变化，尚未确认退出，请重新核对会话。')
  await expect(page).toHaveURL(/\/applications$/)
  expect(control.clears()).toBe(1)
  expect(control.reads()).toBe(readsBefore + 1)
  expect(control.state().session?.accessToken).toBe('fixture-newer-main-token')
})

test('旧清理确认不能让较新的本地会话跳回登录页', async ({ page }) => {
  const control = await setup(page)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect.poll(control.clears).toBe(1)
  await page.evaluate(async () => {
    const modulePath = '/src/stores/userStore.ts'
    const { useUserStore } = await import(modulePath)
    useUserStore().setSession({ accessToken: 'fixture-new-local-token', tokenType: 'Bearer', expiresInSeconds: 300 })
  })
  control.success()
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeEnabled()
  await expect(page).toHaveURL(/\/applications$/)
  expect(await page.evaluate(async () => {
    const modulePath = '/src/stores/userStore.ts'
    const { useUserStore } = await import(modulePath)
    return useUserStore().accessToken === 'fixture-new-local-token'
  })).toBe(true)
  await expect(page.getByRole('alert', { name: '退出登录状态' })).toHaveCount(0)
})

test('Main已清空但导航被阻止时，明确提示页面切换失败', async ({ page }) => {
  const control = await setup(page)
  await page.evaluate(() => {
    const root = document.querySelector('#app') as HTMLElement & {
      __vue_app__: { config: { globalProperties: { $router: Router } } }
    }
    root.__vue_app__.config.globalProperties.$router.beforeEach(to => to.name !== 'login')
  })
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect.poll(control.clears).toBe(1)
  control.success()
  await expect(page.getByRole('alert', { name: '退出登录状态' })).toContainText('退出已确认，但页面切换失败，请刷新页面。')
  expect(control.state().session).toBeNull()
  await expect(page).toHaveURL(/\/applications$/)
  expect(control.pageErrors).toEqual([])
})

test('组件卸载后，迟到清理确认不再触发旧页面导航', async ({ page }) => {
  const control = await setup(page)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect.poll(control.clears).toBe(1)
  await page.evaluate(() => {
    const root = document.querySelector('#app') as HTMLElement & { __vue_app__: { unmount(): void } }
    root.__vue_app__.unmount()
  })
  control.success()
  await expect.poll(() => control.state().session).toBeNull()
  // Wait for the real Store action to settle through the exposed bridge.
  await expect.poll(async () => page.evaluate(async () => {
    const modulePath = '/src/stores/userStore.ts'
    const { useUserStore } = await import(modulePath)
    return useUserStore().clearing
  })).toBe(false)
  await expect(page).toHaveURL(/\/applications$/)
  expect(control.pageErrors).toEqual([])
})
