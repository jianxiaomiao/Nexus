// 复用隔离shell替身；所有业务响应都是固定测试数据，不连接后端。
require('./short-link-boundaries-main.cjs')
const timestamp = '2026-11-14T00:00:00Z'
const link = { id: 123, apiKeyId: 11, name: '界面验收短链', shortCode: 'Ab12Cd34Ef56', originalUrl: 'https://weibo.com/1', status: 0, expiresAt: '2030-01-01T00:00:00Z', createdAt: timestamp, updatedAt: timestamp }
const app = { id: 1, name: '界面验收应用', status: 0, createdAt: timestamp, updatedAt: timestamp, quota: { periodStart: timestamp, periodEnd: '2030-01-01T00:00:00Z', quotaLimit: 1000, usedCount: 0, restCount: 1000 } }
const key = { id: 11, applicationId: 1, name: '界面验收Key', publicId: 'TEST_ONLY', keyPreview: 'TEST_ONLY', status: 0, createdAt: timestamp, updatedAt: timestamp }
const page = records => ({ records, total: records.length, current: 1, size: 10 })
globalThis.fetch = async url => {
  const pathname = new URL(url).pathname
  let data
  if (pathname === '/api/auth/login') data = { accessToken: 'UI_TEST_ONLY', tokenType: 'Bearer', expiresInSeconds: 3600 }
  else if (pathname === '/api/application') data = page([app])
  else if (pathname === '/api/apiKey/1') data = page([key])
  else if (pathname === '/api/short-links') data = page([link])
  else if (pathname === '/v1/short-links') data = globalThis.__shortLinkUiList ? [link] : link
  else throw Error('Unexpected UI test path')
  return Response.json({ code: 'SUCCESS', message: 'Test response', data })
}
