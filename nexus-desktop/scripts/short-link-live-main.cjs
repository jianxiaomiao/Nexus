const electron = require('electron')
const Module = require('node:module')
const path = require('node:path')
const dir = process.env.NEXUS_SHORT_LINK_LIVE_DIR
if (!dir) throw Error('Use test-short-link-live.cjs')
const setPath = electron.app.setPath.bind(electron.app)
electron.app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)
const control = globalThis.__shortLinkLive = { requests: [], links: [], createAttempts: 0, opens: [], nextOpen: null, blocked: 0 }
if (process.env.NEXUS_SHORT_LINK_LIVE_RESUME) {
  const previous = require('node:fs').readFileSync(process.env.NEXUS_SHORT_LINK_LIVE_RESUME, 'utf8')
  const seed = JSON.parse(previous).control
  if (seed.createAttempts !== 1 || seed.links.length !== 1 || seed.opens.length !== 0) throw Error('Unexpected resume checkpoint')
  control.links = seed.links
  control.createAttempts = seed.createAttempts
}
const realFetch = globalThis.fetch.bind(globalThis)
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  const method = options.method
  let allowed = parsed.origin === 'http://localhost:8080' && (
    (method === 'GET' && parsed.pathname.startsWith('/api/')) ||
    (method === 'POST' && parsed.pathname === '/api/auth/login'))
  const create = method === 'POST' && parsed.pathname === '/v1/short-links'
  if (create && parsed.origin === 'http://localhost:8080') {
    const data = JSON.parse(Buffer.from(options.body).toString('utf8'))
    allowed = control.createAttempts < 2 && data.name.startsWith('Electron跳转验收-') && data.originalUrl === 'https://weibo.com/'
    if (allowed) control.createAttempts++
  }
  if (method === 'PUT' && parsed.origin === 'http://localhost:8080' && parsed.pathname === '/api/short-links') {
    const data = JSON.parse(Buffer.from(options.body).toString('utf8'))
    allowed = data.id === control.links[0]?.id && data.status === 1 && Object.keys(data).length === 2
  }
  if (!allowed) { control.blocked++; throw Error('Outside short-link acceptance scope') }
  const record = { path: parsed.pathname, method, status: null }
  control.requests.push(record)
  const response = await realFetch(url, options)
  record.status = response.status
  if (create && response.ok) {
    const { data } = await response.clone().json()
    control.links.push({ id: data.id, apiKeyId: data.apiKeyId, shortCode: data.shortCode, expiresAt: data.expiresAt })
  }
  return response
}
const realOpen = electron.shell.openExternal.bind(electron.shell)
const facade = { ...electron, shell: { openExternal: async target => {
  const expected = control.nextOpen
  if (!expected || target !== `http://localhost:8080/s/${expected}` || !control.links.some(link => link.shortCode === expected)) {
    control.blocked++; throw Error('Unexpected external URL')
  }
  control.nextOpen = null
  const record = { shortCode: expected, handedOff: false }
  control.opens.push(record)
  await realOpen(target)
  record.handedOff = true
} } }
const main = path.resolve(__dirname, '../main.cjs')
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request === 'electron' && parent?.filename === main) return facade
  return originalLoad.call(this, request, parent, isMain)
}
try { require('../main.cjs') } finally { Module._load = originalLoad }
