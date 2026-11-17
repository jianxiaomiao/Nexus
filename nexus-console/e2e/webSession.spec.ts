import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { sampleApplication } from './support/mockApi'

async function fixture(context: BrowserContext) {
  let sid = 100, generation = 0, cookie = '', active = false
  let refreshes = 0, replays = 0, inflight = 0, maxInflight = 0, management = 0, logouts = 0
  let loseRefresh = false, loseLogout = false, rejectManagement = false, rejectLogin = false
  let waitRefresh: Promise<void> | undefined
  let waitLogout: Promise<void> | undefined
  const deadline = new Date(Date.now() + 86_400_000).toISOString()
  await context.route('**/components/login-planet/scene.js*', route => route.abort())
  await context.route(url => url.pathname.startsWith('/api/'), async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    const body = req.postData() ? req.postDataJSON() as { expectedSessionId?: string } : {}
    const sent = /(?:^|;\s*)nexus_rt_dev=([^;]*)/.exec(req.headers().cookie ?? '')?.[1]
    const respond = (data: unknown, status = 200, setCookie?: string) => route.fulfill({ status,
      headers: setCookie ? { 'Set-Cookie': setCookie } : {}, json: { code: status === 200 ? 'SUCCESS' : 'INVALID_ACCESS_TOKEN', message: 'test', data } })
    const tokens = () => ({ accessToken: `test-at-${sid}-${generation}`, tokenType: 'Bearer', expiresInSeconds: 1800,
      sessionId: String(sid), sessionExpiresAt: deadline })
    const rotate = () => { cookie = `test-opaque-${sid}-${++generation}`; return `nexus_rt_dev=${cookie}; HttpOnly; SameSite=Strict; Path=/` }
    if (path.startsWith('/api/auth/')) {
      expect(req.headers()['x-nexus-session-transport']).toBe('cookie')
      expect(req.headers().authorization).toBeUndefined()
    }
    if (path === '/api/auth/login') {
      if (rejectLogin) { await respond(null,401); return }
      active = true; sid++; const header = rotate(); await respond(tokens(), 200, header); return
    }
    if (path === '/api/auth/refresh') {
      refreshes++
      if (!active || !sent) { await respond(null,401); return }
      if (sent !== cookie) { replays++; active = false; await respond(null,401); return }
      if (body.expectedSessionId && body.expectedSessionId !== String(sid)) { await respond(null,401); return }
      inflight++; maxInflight = Math.max(maxInflight,inflight)
      try {
        if (waitRefresh) await waitRefresh
        const header = rotate()
        if (loseRefresh) { loseRefresh = false; await route.abort('failed'); return }
        await respond(tokens(),200,header)
      } finally { inflight-- }
      return
    }
    if (path === '/api/auth/logout') {
      logouts++
      if (waitLogout) await waitLogout
      if (loseLogout) { await route.abort('failed'); return }
      if (body.expectedSessionId && body.expectedSessionId !== String(sid)) { await respond(null,401); return }
      active = false; await respond({ serverRevocation: sent ? 'confirmed' : 'not-needed' },200,'nexus_rt_dev=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/'); return
    }
    management++
    expect(req.headers().authorization).toMatch(/^Bearer test-at-/)
    if (rejectManagement) { await respond(null,401); return }
    await respond({ total: 1, current: 1, size: 10, records: [sampleApplication] })
  })
  return {
    counts: () => ({ refreshes, replays, maxInflight, management, logouts }),
    loseRefresh: () => { loseRefresh = true }, loseLogout: () => { loseLogout = true },
    rejectLogin: () => { rejectLogin = true },
    holdLogout: () => { let release!: () => void; waitLogout = new Promise<void>(r => { release=r }); return () => { waitLogout=undefined; release() } },
    rejectManagement: () => { rejectManagement = true },
    holdRefresh: () => { let release!: () => void; waitRefresh = new Promise<void>(r => { release=r }); return () => { waitRefresh=undefined; release() } },
  }
}
async function signIn(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入邮箱').fill('learner@example.test')
  await page.getByPlaceholder('请输入密码').fill('test-only-password')
  await page.getByRole('button',{ name:'登录',exact:true }).click()
  await expect(page).toHaveURL(/\/applications$/)
  await expect(page.getByRole('article').filter({hasText:'示例应用'})).toBeVisible()
}
async function localState(page: Page) {
  return page.evaluate(async () => {
    const path='/src/stores/userStore.ts'; const {useUserStore}=await import(/* @vite-ignore */ path); const s=useUserStore()
    return { signedIn:!!s.accessToken, sessionId:s.sessionId, revision:s.webRevision, notice:s.sessionNotice }
  })
}
async function renew(page: Page, count=1) {
  return page.evaluate(async count => {
    const path='/src/stores/userStore.ts'; const {useUserStore}=await import(/* @vite-ignore */ path); const s=useUserStore()
    s.expiresAt=Date.now()-1
    const context=s.sessionContext()
    return (await Promise.all(Array.from({length:count},()=>s.ensureSession(context)))).map(r=>r.status)
  },count)
}

test('Cookie不可被脚本读取，AT仅在内存；重载恢复后RT轮换',async ({page,context})=>{
  const api=await fixture(context); await signIn(page)
  const before=api.counts().refreshes
  expect(await page.evaluate(()=>document.cookie)).not.toContain('nexus_rt_dev')
  const cookies=await context.cookies(); expect(cookies.find(c=>c.name==='nexus_rt_dev')).toMatchObject({httpOnly:true,sameSite:'Strict'})
  const persistence=await page.evaluate(()=>({local:{...localStorage},session:{...sessionStorage}}))
  expect(JSON.stringify(persistence)).not.toMatch(/test-at-|test-opaque-|accessToken|refreshToken/)
  await page.reload(); await expect(page).toHaveURL(/\/applications$/)
  await expect.poll(async()=> (await localState(page)).signedIn).toBe(true)
  expect(api.counts().refreshes).toBe(before+1)
  expect(api.counts().replays).toBe(0)
})

test('同标签合并并发刷新，双标签串行轮换且各自拿AT',async ({page,context})=>{
  const api=await fixture(context); await signIn(page)
  let before=api.counts().refreshes
  expect(await renew(page,6)).toEqual(Array(6).fill('ok')); expect(api.counts().refreshes).toBe(before+1)
  const other=await context.newPage(); await other.goto('/applications')
  await expect(other).toHaveURL(/\/applications$/); await expect.poll(async()=> (await localState(other)).signedIn).toBe(true)
  before=api.counts().refreshes
  const results=await Promise.all([renew(page,3),renew(other,3)])
  expect(results.flat()).toEqual(Array(6).fill('ok')); expect(api.counts().refreshes).toBe(before+2)
  expect(api.counts().replays).toBe(0); expect(api.counts().maxInflight).toBe(1)
})

test('另一标签登录会切换身份状态，退出同步清理且重载不复活',async ({page,context})=>{
  const api=await fixture(context); const other=await context.newPage(); await other.goto('/login')
  await signIn(page)
  await expect(other).toHaveURL(/\/applications$/)
  await expect.poll(async()=> (await localState(other)).sessionId).toBe((await localState(page)).sessionId)
  await other.getByRole('button',{name:'退出登录'}).click()
  await expect(page).toHaveURL(/\/login$/); await expect(other).toHaveURL(/\/login$/)
  const before=api.counts().refreshes
  await page.reload(); await expect(page).toHaveURL(/\/login$/)
  expect(api.counts().refreshes).toBe(before)
})

test('续期响应丢失后停止自动重试，显式重新登录可恢复',async ({page,context})=>{
  const api=await fixture(context); await signIn(page); api.loseRefresh()
  expect(await renew(page)).toEqual(['reauthenticate'])
  const before=api.counts().refreshes
  await page.reload(); await expect(page).toHaveURL(/\/login$/)
  expect(api.counts().refreshes).toBe(before)
  await signIn(page); expect(api.counts().replays).toBe(0)
})

test('退出撤销未知仍跨标签阻止Cookie恢复，警告在重载后保留',async ({page,context})=>{
  const api=await fixture(context); await signIn(page)
  const other=await context.newPage(); await other.goto('/applications'); await expect(other).toHaveURL(/\/applications$/)
  api.loseLogout(); await page.getByRole('button',{name:'退出登录'}).click()
  await expect(page).toHaveURL(/\/login$/); await expect(other).toHaveURL(/\/login$/)
  const before=api.counts().refreshes
  await other.reload(); await expect(other).toHaveURL(/\/login$/)
  await expect(other.getByText('已退出本地登录，但服务端撤销未确认。')).toBeVisible()
  expect(api.counts().refreshes).toBe(before)
})

test('刷新中的退出意图阻止迟到AT恢复，并撤销原会话',async ({page,context})=>{
  const api=await fixture(context); await signIn(page)
  const other=await context.newPage(); await other.goto('/applications'); await expect(other).toHaveURL(/\/applications$/)
  const release=api.holdRefresh(), before=api.counts().refreshes
  const renewal=renew(page); await expect.poll(()=>api.counts().refreshes).toBe(before+1)
  await other.getByRole('button',{name:'退出登录'}).click()
  await expect.poll(async()=> (await localState(page)).signedIn).toBe(false)
  release(); expect(await renewal).toEqual(['stale'])
  await expect(other).toHaveURL(/\/login$/); await expect(page).toHaveURL(/\/login$/)
  expect(api.counts().logouts).toBe(1)
})

test('标签崩溃留下working标记时不复用可能已消费RT',async ({page,context})=>{
  const api=await fixture(context); await signIn(page)
  const release=api.holdRefresh(), before=api.counts().refreshes
  void renew(page).catch(()=>{})
  await expect.poll(()=>api.counts().refreshes).toBe(before+1)
  await page.close(); release()
  const other=await context.newPage(); await other.goto('/applications')
  await expect(other).toHaveURL(/\/login$/)
  expect(api.counts().refreshes).toBe(before+1)
})

test('当前401触发续期但不自动重发业务请求，迟到401复用已更新状态',async ({page,context})=>{
  const api=await fixture(context); await signIn(page)
  api.rejectManagement(); const before=api.counts()
  const statuses=await page.evaluate(async()=>{
    const path='/src/api/http.ts';const {http}=await import(/* @vite-ignore */ path)
    return (await Promise.allSettled([http.get('/application'),http.get('/application')])).map(r=>r.status)
  })
  expect(statuses).toEqual(['rejected','rejected'])
  expect(api.counts().management).toBe(before.management+2)
  expect(api.counts().refreshes).toBe(before.refreshes+1)
  expect((await localState(page)).signedIn).toBe(true)
})

test('不支持安全协调能力时阻止登录而不是无锁刷新',async ({page,context})=>{
  const api=await fixture(context)
  await page.addInitScript(()=>Object.defineProperty(navigator,'locks',{value:undefined}))
  await page.goto('/login')
  await expect(page.locator('#app')).toContainText('此浏览器无法安全协调登录状态')
  expect(api.counts().refreshes).toBe(0)
})


test('失败的新登录不能抹掉已有撤销未确认提示',async ({page,context})=>{
  const api=await fixture(context);await signIn(page);api.loseLogout()
  await page.getByRole('button',{name:'退出登录'}).click();await expect(page).toHaveURL(/\/login$/)
  api.rejectLogin()
  await page.getByPlaceholder('请输入邮箱').fill('learner@example.test')
  await page.getByPlaceholder('请输入密码').fill('wrong-password')
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await expect(page.getByText('邮箱或密码错误')).toBeVisible()
  await page.reload();await expect(page.getByText('已退出本地登录，但服务端撤销未确认。')).toBeVisible()
})

test('迟到登录补偿完成不能覆盖其他标签已发布的退出意图',async ({page,context})=>{
  const api=await fixture(context);await page.goto('/login');await page.getByPlaceholder('请输入邮箱').waitFor()
  const other=await context.newPage();await other.goto('/login');await other.getByPlaceholder('请输入邮箱').waitFor()
  const release=api.holdLogout()
  const late=page.evaluate(async()=>{
    const path='/src/auth/webSession.ts';const {webSession}=await import(/* @vite-ignore */ path);let checks=0
    return (await webSession().login({email:'learner@example.test',password:'test-only-password'},()=>++checks===1)).status
  })
  await expect.poll(()=>api.counts().logouts).toBe(1)
  const logout=other.evaluate(async()=>{
    const path='/src/auth/webSession.ts';const {webSession}=await import(/* @vite-ignore */ path)
    return webSession().logout(webSession().read().generation,'')
  })
  await expect.poll(()=>other.evaluate(()=>JSON.parse(localStorage.getItem('nexus.auth.web.v1')!).operation)).toBe('logout')
  release();expect(await late).toBe('stale');expect((await logout).status).toBe('ok')
  expect(await other.evaluate(()=>JSON.parse(localStorage.getItem('nexus.auth.web.v1')!).operation)).toBe('logout')
  expect(api.counts().logouts).toBe(2)
})

test('广播尚未送达时也不能应用已被退出意图覆盖的续期快照',async ({page,context})=>{
  await fixture(context);await signIn(page)
  const result=await page.evaluate(async()=>{
    const modulePath='/src/auth/webSession.ts', storePath='/src/stores/userStore.ts'
    const {webSession}=await import(/* @vite-ignore */ modulePath)
    const {useUserStore}=await import(/* @vite-ignore */ storePath)
    const s=useUserStore(), coordinator=webSession(), original=coordinator.restore
    coordinator.restore=async (...args: Parameters<typeof original>)=>{
      const result=await original(...args)
      // Simulate another process committing logout before its event reaches this tab.
      const state=coordinator.read()
      localStorage.setItem('nexus.auth.web.v1',JSON.stringify({...state,generation:crypto.randomUUID(),operationId:crypto.randomUUID(),phase:'logged-out',operation:'logout',revocation:'pending'}))
      return result
    }
    try {s.expiresAt=Date.now()-1; const result=await s.ensureSession(s.sessionContext());return {status:result.status,empty:!s.accessToken}}
    finally {coordinator.restore=original}
  })
  expect(result).toEqual({status:'stale',empty:true})
  await expect(page).toHaveURL(/\/login$/)
})
