const { app, session } = require('electron')
const fs = require('node:fs')
const path = require('node:path')

// 测试入口：隔离用户数据，不与已经打开的普通窗口争用缓存和状态文件。
const runtime = path.resolve(__dirname, '..', '.runtime')
fs.mkdirSync(runtime, { recursive: true })
const testDir = fs.mkdtempSync(path.join(runtime, 'standalone-smoke-'))
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? testDir : value)

let remoteRequests = 0
app.whenReady().then(() => {
  // 即使机器已有 Vite，也不允许测试页面借助 HTTP/HTTPS 加载。
  session.defaultSession.webRequest.onBeforeRequest(
    { urls: ['http://*/*', 'https://*/*'] },
    (_details, callback) => {
      remoteRequests++
      callback({ cancel: true })
    },
  )
})
app.on('will-quit', () => {
  console.log(`[standalone smoke] HTTP/HTTPS requests blocked: ${remoteRequests}`)
  if (remoteRequests > 0) process.exitCode = 1
})
require('../main.cjs')
