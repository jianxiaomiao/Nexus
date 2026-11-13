// 测试入口替换通知与网络边界；正式Main/Preload/IPC不变。
const electron = require('electron')
const { EventEmitter } = require('node:events')
const NativeNotification = electron.Notification
const control = globalThis.__notificationTest = {
  mode: 'mock', supportChecks: 0, attempts: [], nativeInstances: [],
  shown: 0, failed: 0, nativeErrors: [], unhandled: 0,
}
process.on('unhandledRejection', () => { control.unhandled++ })

function NotificationProbe(options) {
  const mode = control.mode
  control.attempts.push({ mode, title: options.title, body: options.body })
  if (mode === 'constructor-throw') throw Error('Controlled constructor failure')
  if (mode === 'native') {
    const notification = new NativeNotification(options)
    control.nativeInstances.push(notification)
    notification.on('show', () => { control.shown++ })
    notification.on('failed', (_event, error) => {
      control.failed++
      control.nativeErrors.push(String(error)) // 固定测试通知，不涉及凭据。
    })
    return notification
  }
  const notification = new EventEmitter()
  notification.show = () => {
    if (mode === 'show-throw') throw Error('Controlled show failure')
    if (mode === 'async-failed') {
      setImmediate(() => { control.failed++; notification.emit('failed', {}, 'Controlled native failure') })
    }
  }
  return notification
}
NotificationProbe.isSupported = () => {
  control.supportChecks++
  if (control.mode === 'support-throw') throw Error('Controlled support check failure')
  if (control.mode === 'unsupported') return false
  return control.mode === 'native' ? NativeNotification.isSupported() : true
}
// Electron命名导出不可重定义；仅为两个测试目标的require返回独立门面。
const Module = require('node:module')
const path = require('node:path')
const targets = new Set([
  path.resolve(__dirname, '../main.cjs'),
  path.resolve(__dirname, 'tracking-boundaries-main.cjs'),
])
const facade = {
  app: electron.app, BrowserWindow: electron.BrowserWindow, dialog: electron.dialog,
  session: electron.session, screen: electron.screen, ipcMain: electron.ipcMain,
  Notification: NotificationProbe,
}
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request === 'electron' && targets.has(parent?.filename)) return facade
  return originalLoad.call(this, request, parent, isMain)
}
try { require('./tracking-boundaries-main.cjs') } finally { Module._load = originalLoad }
