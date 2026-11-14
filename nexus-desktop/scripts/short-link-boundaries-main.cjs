// 隔离测试入口：只替换系统打开能力，不替换正式Main处理器或Preload。
const electron = require('electron')
const Module = require('node:module')
const path = require('node:path')
const dir = process.env.NEXUS_SHORT_LINK_BOUNDARY_DIR
if (!dir) throw Error('Use test-short-link-boundaries.cjs')
const setPath = electron.app.setPath.bind(electron.app)
electron.app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)
const control = globalThis.__shortLinkBoundary = {
  calls: [], mode: 'success', release: null, networkCalls: 0, unhandled: 0,
}
globalThis.fetch = async () => { control.networkCalls++; throw Error('Unexpected test network request') }
process.on('unhandledRejection', () => { control.unhandled++ })
const facade = { ...electron, shell: { openExternal(target) {
  control.calls.push(target)
  if (control.mode === 'throw') throw Error('Controlled synchronous failure')
  if (control.mode === 'reject') return Promise.reject(Error('Controlled system rejection'))
  if (control.mode === 'pending') return new Promise(resolve => { control.release = resolve })
  return Promise.resolve()
} } }
const target = path.resolve(__dirname, '../main.cjs')
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request === 'electron' && parent?.filename === target) return facade
  return originalLoad.call(this, request, parent, isMain)
}
try { require('../main.cjs') } finally { Module._load = originalLoad }
