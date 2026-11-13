// 隔离验收入口：正式Main/Preload不变，只让一次状态查询失败。
const { app } = require('electron')
const path = require('node:path')
const dir = process.env.NEXUS_RESUME_ACCEPTANCE_DIR
if (!dir) throw Error('Use test-resume-acceptance.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)

const realFetch = globalThis.fetch.bind(globalThis)
const control = globalThis.__resumeAcceptance = { injected: false, requests: [] }
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  const match = /^\/v1\/web\/screenshot-jobs\/([0-9a-f-]{36})(\/result)?$/.exec(parsed.pathname)
  if (parsed.origin !== 'http://localhost:8080' || !match) throw Error('Unexpected acceptance destination')
  const record = { jobId: match[1], kind: match[2] ? 'result' : 'status', injected: false, status: null }
  control.requests.push(record) // 不收集headers、Key或响应内容。
  if (!match[2] && !control.injected) {
    control.injected = true
    record.injected = true
    record.status = 503
    return new Response(null, { status: 503 })
  }
  const response = await realFetch(url, options)
  record.status = response.status
  return response
}
require('../main.cjs')
