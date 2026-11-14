// 隔离验收入口：默认注入一次查询失败；通知验收模式不注入失败。
const electron = require('electron')
const { app } = electron
const path = require('node:path')
const dir = process.env.NEXUS_RESUME_ACCEPTANCE_DIR
if (!dir) throw Error('Use test-resume-acceptance.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)

const realFetch = globalThis.fetch.bind(globalThis)
const notificationAcceptance = process.env.NEXUS_RESUME_ACCEPTANCE_NOTIFY === '1'
const handoffAcceptance = process.env.NEXUS_RESUME_ACCEPTANCE_HANDOFF === '1'
const control = globalThis.__resumeAcceptance = {
  injected: false, requests: [], notificationAttempts: 0, notificationShown: 0,
  notificationErrors: [], nativeNotifications: [], handoffCalls: [],
}
globalThis.fetch = async (url, options) => {
  const parsed = new URL(url)
  const match = /^\/v1\/web\/screenshot-jobs\/([0-9a-f-]{36})(\/result)?$/.exec(parsed.pathname)
  if (parsed.origin !== 'http://localhost:8080' || !match) throw Error('Unexpected acceptance destination')
  const record = { jobId: match[1], kind: match[2] ? 'result' : 'status', injected: false, status: null }
  control.requests.push(record) // 不收集headers、Key或响应内容。
  if (!notificationAcceptance && !handoffAcceptance && !match[2] && !control.injected) {
    control.injected = true
    record.injected = true
    record.status = 503
    return new Response(null, { status: 503 })
  }
  const response = await realFetch(url, options)
  record.status = response.status
  return response
}
if (!notificationAcceptance && !handoffAcceptance) {
  require('../main.cjs')
} else {
  // 包装真实Notification仅观察事件，不替换通知行为或Electron只读导出。
  const NativeNotification = electron.Notification
  function ObservedNotification(options) {
    control.notificationAttempts++
    const notification = new NativeNotification(options)
    control.nativeNotifications.push(notification)
    notification.on('show', () => { control.notificationShown++ })
    notification.on('failed', (_event, error) => { control.notificationErrors.push(String(error)) })
    return notification
  }
  ObservedNotification.isSupported = () => NativeNotification.isSupported()
  const Module = require('node:module')
  const target = path.resolve(__dirname, '../main.cjs')
  const originalLoad = Module._load
  const facade = {
    app, BrowserWindow: electron.BrowserWindow, dialog: electron.dialog,
    session: electron.session, screen: electron.screen,
    ipcMain: {
      handle(channel, handler) {
        electron.ipcMain.handle(channel, (event, ...args) => {
          if (handoffAcceptance && channel === 'screenshot-tracking:start') {
            const record = { jobId: args[0]?.jobId ?? null, injected: control.handoffCalls.length === 0 }
            control.handoffCalls.push(record) // 不记录传入的Key。
            if (record.injected) return { status: 'failed', code: 'BUSY' }
          }
          return handler(event, ...args)
        })
      },
    },
    Notification: ObservedNotification,
  }
  Module._load = function (request, parent, isMain) {
    if (request === 'electron' && parent?.filename === target) return facade
    return originalLoad.call(this, request, parent, isMain)
  }
  try { require('../main.cjs') } finally { Module._load = originalLoad }
}
