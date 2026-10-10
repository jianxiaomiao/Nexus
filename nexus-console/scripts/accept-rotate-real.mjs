import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { chromium, expect } from '@playwright/test'

const apiOrigin = process.env.NEXUS_ACCEPT_API_ORIGIN ?? 'http://localhost:8080'
const consoleOrigin = process.env.NEXUS_ACCEPT_CONSOLE_ORIGIN ?? 'http://localhost:5173'
const suffix = randomBytes(8).toString('hex')
const email = `rotate-accept-${suffix}@example.test`
const password = randomBytes(24).toString('base64url')
let bearer = ''
let applicationId
let browser
let accepted = false

async function call(path, { method = 'GET', body, authorization } = {}) {
  const response = await fetch(new URL(path, apiOrigin), {
    method,
    headers: {
      ...(body && { 'Content-Type': 'application/json' }),
      ...(authorization && { Authorization: authorization }),
    },
    ...(body && { body: JSON.stringify(body) }),
  })
  assert(response.ok, `${method} ${path.split('?')[0]} returned HTTP ${response.status}`)
  const result = await response.json()
  assert.equal(result.code, 'SUCCESS', `${method} ${path.split('?')[0]} did not succeed`)
  return result.data
}

async function machineUuid(key) {
  return fetch(new URL('/v1/utils/uuid', apiOrigin), {
    headers: { Authorization: `ApiKey ${key}` },
  })
}

async function waitForUsage(apiKeyId, minimum) {
  const params = new URLSearchParams({ applicationId: String(applicationId), apiKeyId: String(apiKeyId), timeRange: 'TODAY' })
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const summary = await call(`/api/usage?${params}`, { authorization: `Bearer ${bearer}` })
    if (summary.allTimeCount >= minimum) return summary.allTimeCount
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`Usage count did not reach ${minimum} within 10 seconds`)
}

try {
  await call('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Rotate acceptance ${suffix}`, password },
  })
  const login = await call('/api/auth/login', { method: 'POST', body: { email, password } })
  bearer = login.accessToken
  const application = await call('/api/application/create', {
    method: 'POST', body: { name: `rotate-accept-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  applicationId = application.id
  const key = await call('/api/apiKey/create', {
    method: 'POST', body: { applicationId, name: `rotate-accept-key-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  assert.equal((await machineUuid(key.apiKey)).status, 200)
  const link = await call('/v1/short-links', {
    method: 'POST', authorization: `ApiKey ${key.apiKey}`,
    body: {
      name: `rotate-accept-link-${suffix}`,
      originalUrl: 'https://www.douyin.com/video/1',
      expiresAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
    },
  })
  const historicalCount = await waitForUsage(key.id, 2)

  browser = await chromium.launch()
  const page = await browser.newPage()
  await page.goto(`${consoleOrigin}/login`)
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL('**/applications')
  await page.getByRole('article').filter({ hasText: application.name }).getByRole('link', { name: '查看详情' }).click()
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page.getByText(key.publicId, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '轮换凭据' }).click()
  const confirmation = page.getByRole('dialog', { name: '轮换 API Key 凭据' })
  await expect(confirmation).toContainText('旧凭据立即失效')
  const rotationResponse = page.waitForResponse((response) => response.url().endsWith(`/api/apiKey/${key.id}/rotate`))
  await confirmation.getByRole('button', { name: '确认轮换' }).click()
  assert.equal((await rotationResponse).status(), 200)

  const reveal = page.getByRole('dialog', { name: '轮换成功' })
  await expect(reveal).toBeVisible()
  const newKey = await reveal.getByRole('textbox', { name: '新的 API Key（仅显示一次）' }).inputValue()
  if (!newKey || newKey === key.apiKey) throw new Error('Rotation did not return a different credential')
  await expect(reveal.getByRole('button', { name: '我已保存，关闭' })).toBeDisabled()

  assert.equal((await machineUuid(key.apiKey)).status, 401)
  assert.equal((await machineUuid(newKey)).status, 200)
  const links = await call('/v1/short-links', { authorization: `ApiKey ${newKey}` })
  assert(links.some((item) => item.id === link.id && item.apiKeyId === key.id), 'Existing short link is not owned by the rotated Key')
  const redirect = await fetch(new URL(`/s/${link.shortCode}`, apiOrigin), { redirect: 'manual' })
  assert.equal(redirect.status, 302)
  assert.equal(redirect.headers.get('location'), 'https://www.douyin.com/video/1')
  const currentCount = await waitForUsage(key.id, historicalCount + 2)
  assert(currentCount >= historicalCount + 2, 'Usage history was not retained')

  await reveal.getByText('我已安全保存新的 API Key').click()
  await reveal.getByRole('button', { name: '我已保存，关闭' }).click()
  await expect(reveal).not.toBeVisible()
  await expect(page.locator('#rotated-api-key')).toHaveCount(0)
  const listed = await call(`/api/apiKey/${applicationId}?apiKeyId=${key.id}`, {
    authorization: `Bearer ${bearer}`,
  })
  assert.equal(listed.records.length, 1)
  assert.equal(listed.records[0].id, key.id)
  assert.notEqual(listed.records[0].publicId, key.publicId)
  await expect(page.getByText(listed.records[0].publicId, { exact: true })).toBeVisible()
  accepted = true
} finally {
  await browser?.close()
  if (applicationId && bearer) {
    await call(`/api/application/${applicationId}`, { method: 'DELETE', authorization: `Bearer ${bearer}` })
  }
}
if (accepted) console.log(`PASS: real browser rotation, old 401, new 200, short-link and Usage continuity; Application ${applicationId} soft-deleted`)
