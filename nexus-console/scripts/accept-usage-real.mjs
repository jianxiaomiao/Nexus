import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { chromium, expect } from '@playwright/test'

const apiOrigin = process.env.NEXUS_ACCEPT_API_ORIGIN ?? 'http://localhost:8080'
const consoleOrigin = process.env.NEXUS_ACCEPT_CONSOLE_ORIGIN ?? 'http://localhost:5173'
const suffix = randomBytes(8).toString('hex')
const email = `usage-accept-${suffix}@example.test`
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

async function usage(apiKeyId, timeRange = 'TODAY') {
  const params = new URLSearchParams({ applicationId: String(applicationId), apiKeyId: String(apiKeyId), timeRange })
  return call(`/api/usage?${params}`, { authorization: `Bearer ${bearer}` })
}

async function waitForUsage(apiKeyId, expected) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const summary = await usage(apiKeyId)
    if (summary.allTimeCount === expected && summary.periodCount === expected) return summary
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`Usage write did not reach ${expected} within 10 seconds`)
}

async function machineUuid(key) {
  return fetch(new URL('/v1/utils/uuid', apiOrigin), {
    headers: { Authorization: `ApiKey ${key}` },
  })
}

function nextDate(date) {
  const [year, month, day] = date.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10)
}

async function openUsageTab(page, applicationName) {
  await page.getByRole('link', { name: 'Nexus 控制台首页' }).click()
  await page.getByRole('article').filter({ hasText: applicationName }).getByRole('link', { name: '查看详情' }).click()
  await page.getByRole('navigation', { name: '应用详情分区' }).getByRole('link', { name: 'API Keys' }).click()
  await page.getByRole('link', { name: '查看详情' }).click()
  const response = page.waitForResponse((item) => item.url().includes('/api/usage?'))
  await page.getByRole('navigation', { name: 'API Key 详情分区' }).getByRole('link', { name: '调用统计' }).click()
  return response
}

try {
  await call('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Usage acceptance ${suffix}`, password },
  })
  const login = await call('/api/auth/login', { method: 'POST', body: { email, password } })
  bearer = login.accessToken
  const application = await call('/api/application/create', {
    method: 'POST', body: { name: `usage-accept-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  applicationId = application.id
  const key = await call('/api/apiKey/create', {
    method: 'POST', body: { applicationId, name: `usage-accept-key-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  assert.equal((await machineUuid(key.apiKey)).status, 200)
  const first = await waitForUsage(key.id, 1)
  assert(first.apiCounts.some((item) => item.apiCode === 'uuid.generate' && item.count === 1))

  browser = await chromium.launch()
  const page = await browser.newPage()
  await page.goto(`${consoleOrigin}/login`)
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL('**/applications')

  assert.equal((await openUsageTab(page, application.name)).status(), 200)
  await expect(page.getByRole('heading', { name: '调用统计' })).toBeVisible()
  await expect(page.locator('.metric-card').first().locator('strong')).toHaveText('1')
  await expect(page.locator('.api-row').filter({ hasText: 'uuid.generate' })).toContainText('1 次')
  await expect(page.locator('body')).not.toContainText(key.apiKey)

  const todayResponse = page.waitForResponse((response) => response.url().includes('/api/usage?') && response.url().includes('timeRange=TODAY'))
  await page.locator('.range-select').click()
  await page.getByRole('option', { name: '今天' }).click()
  assert.equal((await todayResponse).status(), 200)
  await expect(page.locator('.metric-card').nth(1).locator('strong')).toHaveText('1')

  const today = first.dailyCounts.find((item) => item.count === 1)?.date
  assert(today, 'Expected one Beijing-day usage row')
  await page.locator('.range-select').click()
  await page.getByRole('option', { name: '自定义' }).click()
  await page.getByLabel('开始时间（北京时间）').fill(`${today}T00:00`)
  await page.getByLabel('结束时间（北京时间，不含）').fill(`${nextDate(today)}T00:00`)
  const customResponse = page.waitForResponse((response) => response.url().includes('/api/usage?') && response.url().includes('timeRange=CUSTOM'))
  await page.getByRole('button', { name: '查询' }).click()
  assert.equal((await customResponse).status(), 200)
  await expect(page.locator('.metric-card').nth(1).locator('strong')).toHaveText('1')

  await call('/api/apiKey', {
    method: 'PUT', body: { applicationId, apiKeyId: key.id, status: 1 }, authorization: `Bearer ${bearer}`,
  })
  assert.equal((await machineUuid(key.apiKey)).status, 403)
  assert.equal((await openUsageTab(page, application.name)).status(), 200)
  await expect(page.getByText('已禁用', { exact: true })).toBeVisible()
  await expect(page.locator('.metric-card').first().locator('strong')).toHaveText('1')
  assert.equal((await usage(key.id)).allTimeCount, 1)

  await call('/api/apiKey', {
    method: 'PUT', body: { applicationId, apiKeyId: key.id, status: 0 }, authorization: `Bearer ${bearer}`,
  })
  assert.equal((await machineUuid(key.apiKey)).status, 200)
  await waitForUsage(key.id, 2)
  accepted = true
} finally {
  await browser?.close()
  if (applicationId && bearer) {
    await call(`/api/application/${applicationId}`, { method: 'DELETE', authorization: `Bearer ${bearer}` })
  }
}
if (accepted) console.log(`PASS: real login, fresh Key, UUID recording, Usage page, time ranges, disabled history, 403, re-enable; Application ${applicationId} soft-deleted`)
