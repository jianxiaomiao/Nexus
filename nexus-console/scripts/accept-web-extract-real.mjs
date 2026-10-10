import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'

const apiOrigin = process.env.NEXUS_ACCEPT_API_ORIGIN ?? 'http://localhost:8080'
const articleUrl = process.env.NEXUS_ACCEPT_WEB_URL ?? 'https://blog.csdn.net/qq_46429858/article/details/115696591'
const suffix = randomBytes(8).toString('hex')
const email = `extract-accept-${suffix}@example.test`
const password = randomBytes(24).toString('base64url')
let bearer = ''
let applicationId

async function request(path, { method = 'GET', body, authorization } = {}) {
  const response = await fetch(new URL(path, apiOrigin), {
    method,
    headers: {
      ...(body && { 'Content-Type': 'application/json' }),
      ...(authorization && { Authorization: authorization }),
    },
    ...(body && { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(20_000),
  })
  return { status: response.status, body: await response.json() }
}

async function management(path, options = {}) {
  const response = await request(path, options)
  assert(response.status >= 200 && response.status < 300, `Management ${path} failed with HTTP ${response.status}`)
  assert.equal(response.body.code, 'SUCCESS')
  return response.body.data
}

try {
  await management('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Extract acceptance ${suffix}`, password },
  })
  const login = await management('/api/auth/login', { method: 'POST', body: { email, password } })
  bearer = login.accessToken
  const application = await management('/api/application/create', {
    method: 'POST', body: { name: `extract-accept-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  applicationId = application.id
  const key = await management('/api/apiKey/create', {
    method: 'POST', body: { applicationId, name: `extract-key-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  const extract = (url, apiKey = key.apiKey) => request('/v1/web/extract', {
    method: 'POST', authorization: `ApiKey ${apiKey}`, body: { url },
  })
  const invalidKey = await extract(articleUrl, 'NOT_A_REAL_API_KEY')
  assert.equal(invalidKey.status, 401)
  const invalidTarget = await extract('https://www.google.com/search?q=Nexus')
  assert.equal(invalidTarget.status, 400)
  assert.equal(invalidTarget.body.code, 'INVALID_WEB_EXTRACT_REQUEST')
  const article = await extract(articleUrl)
  console.log(`Live extraction: HTTP ${article.status}, code=${article.body.code}`)
  if (article.status !== 200) console.log(`Upstream result: ${article.body.message}`)
  assert.equal(article.status, 200, 'The real article could not be extracted; inspect the upstream result above')
  assert.equal(article.body.code, 'SUCCESS')
  const data = article.body.data
  assert.equal(typeof data.title, 'string')
  assert(data.textContent.length >= 80, 'Expected article text')
  assert(data.contentHtml.length > 0, 'Expected article HTML')
  assert(!/<script\b|\son\w+\s*=/i.test(data.contentHtml), 'Unexpected executable article markup')
  console.log(`PASS: new Key, invalid Key 401, denied target 400, article 200; text=${data.textContent.length} chars`)
} finally {
  if (applicationId && bearer) {
    await management(`/api/application/${applicationId}`, { method: 'DELETE', authorization: `Bearer ${bearer}` })
    console.log(`Cleanup: Application ${applicationId} soft-deleted; random account remains in the disposable test database`)
  }
}
