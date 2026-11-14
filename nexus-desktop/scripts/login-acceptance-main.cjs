const { app } = require('electron')
const path = require('node:path')
const dir = process.env.NEXUS_LOGIN_ACCEPTANCE_DIR
if (!dir) throw Error('Use test-login-acceptance.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)

// 仅观察正式 Main 的转发，不收集凭据、请求体、查询参数或响应正文。
globalThis.__loginAcceptance = { requests: [] }
const realFetch = globalThis.fetch.bind(globalThis)
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  if (parsed.origin !== 'http://localhost:8080' || !parsed.pathname.startsWith('/api/')) {
    throw Error('Unexpected login acceptance destination')
  }
  const record = {
    path: parsed.pathname,
    method: options.method,
    hasBearer: options.headers.get('Authorization')?.startsWith('Bearer ') ?? false,
    status: null,
  }
  globalThis.__loginAcceptance.requests.push(record)
  const response = await realFetch(url, options)
  record.status = response.status
  return response
}
require('../main.cjs')
