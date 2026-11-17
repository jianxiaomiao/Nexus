/** Opt-in real browser acceptance, launched by WebSessionBrowserIntegrationTests.
 * Credentials come only from a disposable test fixture. No traces, token output or business jobs.
 */
import assert from 'node:assert/strict'
import { chromium } from '@playwright/test'

const baseURL=process.env.NEXUS_WEB_TEST_ORIGIN
assert.equal(baseURL,'http://127.0.0.1:4174')
assert.ok(process.env.NEXUS_WEB_TEST_EMAIL)
assert.ok(process.env.NEXUS_WEB_TEST_PASSWORD)
const browser=await chromium.launch({channel:process.env.NEXUS_TEST_CHROME_CHANNEL || undefined})
const context=await browser.newContext({baseURL})
let rotations=0
await context.route('**/components/login-planet/scene.js*',route=>route.abort())
context.on('request',request=>{ if (new URL(request.url()).pathname==='/api/auth/refresh') rotations++ })
try {
 const page=await context.newPage()
 await page.goto('/login')
 await page.getByPlaceholder('请输入邮箱').fill(process.env.NEXUS_WEB_TEST_EMAIL)
 await page.getByPlaceholder('请输入密码').fill(process.env.NEXUS_WEB_TEST_PASSWORD)
 const responsePromise=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/auth/login')
 await page.getByRole('button',{name:'登录',exact:true}).click()
 const response=await responsePromise
 assert.equal(response.status(),200)
 const loginData=(await response.json()).data
 assert.ok(!Object.hasOwn(loginData,'refreshToken'))
 await page.waitForURL('**/applications')
 const cookie=async()=> (await context.cookies()).find(c=>c.name==='nexus_rt_dev')
 const first=await cookie(); assert.ok(first?.httpOnly); assert.equal(first.sameSite,'Strict')
 assert.equal(await page.evaluate(()=>document.cookie.includes('nexus_rt_dev')),false)
 const persistent=await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))
 assert.ok(!persistent.includes(loginData.accessToken)); assert.ok(!persistent.includes(first.value))
 await page.reload(); await page.waitForURL('**/applications')
 await page.getByRole('heading',{name:'Applications',exact:true}).waitFor()
 assert.ok((await cookie()).value!==first.value,'Reload must rotate the refresh credential')
 const other=await context.newPage(); await other.goto('/applications')
 await other.getByRole('heading',{name:'Applications',exact:true}).waitFor()
 const snapshot=async p=>p.evaluate(async()=>{
  const path='/src/stores/userStore.ts'; const {useUserStore}=await import(path);const s=useUserStore()
  return {sid:s.sessionId,expires:s.expiresAt,at:s.accessToken}
 })
 const [one,two]=await Promise.all([snapshot(page),snapshot(other)])
 assert.equal(one.sid,two.sid); assert.equal(one.sid,loginData.sessionId)
 // Wait for real server-issued JWTs, not a client-only shortened deadline.
 const until=Math.max(one.expires,two.expires)-Date.now()+350
 if (until>0) await new Promise(resolve=>setTimeout(resolve,until))
 const expired=await context.request.get('/api/application',{headers:{Authorization:`Bearer ${one.at}`}})
 assert.equal(expired.status(),401,'The original JWT must really have expired at the server')
 const before=rotations
 const ensure=async p=>p.evaluate(async()=>{
  const path='/src/stores/userStore.ts';const {useUserStore}=await import(path);const s=useUserStore();const ctx=s.sessionContext()
  return (await Promise.all([s.ensureSession(ctx),s.ensureSession(ctx),s.ensureSession(ctx)])).map(r=>r.status)
 })
 const renewed=await Promise.all([ensure(page),ensure(other)])
 assert.deepEqual(renewed.flat(),Array(6).fill('ok'));assert.equal(rotations,before+2)
 const latest=(await snapshot(page)).at, latestRt=(await cookie()).value
 const management=await context.request.get('/api/application',{headers:{Authorization:`Bearer ${latest}`}})
 assert.equal(management.status(),200)
 await other.getByRole('button',{name:'退出登录'}).click()
 await Promise.all([page.waitForURL('**/login'),other.waitForURL('**/login')])
 assert.equal(await cookie(),undefined)
 const rejected=await context.request.get('/api/application',{headers:{Authorization:`Bearer ${latest}`}})
 assert.equal(rejected.status(),401)
 // Legacy JSON transport is isolated from Cookie transport and can prove server revocation.
 const rejectedRt=await context.request.post('/api/auth/refresh',{data:{refreshToken:latestRt}})
 assert.equal(rejectedRt.status(),401)
 const after=rotations
 await page.reload();await page.waitForURL('**/login');assert.equal(rotations,after)
 console.log('LIVE_WEB_ACCEPTANCE_OK: login/cookie/reload/natural-JWT-expiry/two-tabs/rotation/logout/server-revocation')
} finally {await context.close();await browser.close()}
