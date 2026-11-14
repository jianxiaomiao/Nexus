const { app } = require('electron')
const path = require('node:path')
const dir = process.env.NEXUS_UTILS_ACCEPTANCE_DIR
if (!dir) throw Error('Use test-utils-acceptance.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)

const control = globalThis.__utilsAcceptance = { requests: [], blocked: 0 }
const realFetch = globalThis.fetch.bind(globalThis)
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  const isUuid = parsed.pathname === '/v1/utils/uuid' && options.method === 'GET'
  const isHash = parsed.pathname === '/v1/utils/hash' && options.method === 'POST'
  const isManagement = parsed.pathname.startsWith('/api/') &&
    (options.method === 'GET' || (parsed.pathname === '/api/auth/login' && options.method === 'POST'))
  const auth = options.headers.get('Authorization') ?? ''
  const authKind = auth.startsWith('ApiKey ') ? 'ApiKey' : auth.startsWith('Bearer ') ? 'Bearer' : 'none'
  const duplicate = (isUuid || isHash) && authKind === 'ApiKey' && control.requests.some(record =>
    record.path === parsed.pathname && record.authKind === 'ApiKey')
  if (parsed.origin !== 'http://localhost:8080' || (!isUuid && !isHash && !isManagement) || duplicate) {
    control.blocked++
    throw Error('Request outside utils acceptance scope')
  }
  // 仅记录方法、路径、凭据类型、状态；不保存凭据、请求体和响应正文。
  const record = { path: parsed.pathname, method: options.method, authKind, status: null }
  control.requests.push(record)
  const response = await realFetch(url, options)
  record.status = response.status
  return response
}
require('../main.cjs')
