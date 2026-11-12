const { app, BrowserWindow, dialog, session, screen, ipcMain } = require('electron')
const fs = require('node:fs/promises')
const path = require('node:path')
const {
  loadWindowState,
  resolveWindowBounds,
  saveWindowState,
} = require('./window-state.cjs')


const DEV_ORIGIN = 'http://127.0.0.1:5178'
const smoke = process.env.NEXUS_DESKTOP_SMOKE === '1'
let mainWindow

// 当前仅实现开发模式，独立构建的页面入口留到下一检查点。
app.setPath('userData', path.join(__dirname, '.runtime', 'user-data'))
if (app.isPackaged) {
  app.whenReady().then(() => { dialog.showErrorBox('Nexus', '独立构建入口尚未实现，请使用 npm run dev。'); app.exit(1) })
} else {
  app.whenReady().then(async () => {
    console.log('[desktop main] Electron ready; creating BrowserWindow')
    session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false))
    session.defaultSession.setPermissionCheckHandler(() => false)

    const workAreas = screen.getAllDisplays().map(display => display.workArea)
    const primaryArea = screen.getPrimaryDisplay().workArea

    const statePath = path.join(
        app.getPath('userData'), 'window-state.json',
    )
    const savedState = loadWindowState(statePath)

    const bounds = resolveWindowBounds(savedState, workAreas, primaryArea)

    mainWindow = new BrowserWindow({
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
      minWidth: 800, minHeight: 600,
      title: 'Nexus', show: false, autoHideMenuBar: true,
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        webSecurity: true,
        preload: path.join(__dirname, 'preload.cjs')
      },
    })
    mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
    mainWindow.webContents.on('did-fail-load', (_event, code, _description, _url, isMainFrame) => {
      if (isMainFrame) console.error(`[desktop main] Main document load failed: ${code}`)
    })
    mainWindow.webContents.on('render-process-gone', (_event, details) => {
      console.error(`[desktop main] Renderer exited: ${details.reason}, code=${details.exitCode}`)
    })
    mainWindow.on('unresponsive', () => console.error('[desktop main] Window became unresponsive'))
    mainWindow.webContents.on('will-navigate', (event, url) => {
      if (new URL(url).origin !== DEV_ORIGIN) event.preventDefault()
    })

    let userMoved = false
    let userResized = false

    mainWindow.on('will-move', () => {
      userMoved = true
    })

    mainWindow.on('will-resize', () => {
      userResized = true
    })

    mainWindow.on('close', () => {
      if (smoke) return

      const boundsToSave = { ...bounds }

      if (userResized) {
        // 读取 getNormalBounds()
        const tempBound = mainWindow.getNormalBounds()
        boundsToSave.x = tempBound.x
        boundsToSave.y = tempBound.y
        boundsToSave.width = tempBound.width
        boundsToSave.height = tempBound.height
        // 用它更新 boundsToSave 的 x、y、width、height
      } else if (userMoved) {
        // 读取 getNormalBounds()
        const tempBound = mainWindow.getNormalBounds()
        boundsToSave.x = tempBound.x
        boundsToSave.y = tempBound.y
        // 只更新 boundsToSave 的 x、y，保留 width、height
      }

      const state = {
        version: 1,
        bounds: boundsToSave,
        maximized: mainWindow.isMaximized(),
      }

      saveWindowState(statePath, state)
    })

    mainWindow.on('closed', () => { mainWindow = null })
    mainWindow.once('ready-to-show', () => {
      console.log('[desktop main] First frame ready')
      if (!smoke) {
        if (savedState?.maximized) {
          mainWindow.maximize()
        }
        mainWindow.show()
      }
    })

    let screenshotSaveBusy = false

    ipcMain.handle('screenshot:save', async (event, bytes) => {
      // 窗口、网页内容、主框架和来源必须全部匹配；失效的框架也拒绝。
      const owner = mainWindow
      try {
        if (!owner || owner.isDestroyed()
          || event.sender !== owner.webContents
          || !event.senderFrame
          || event.senderFrame !== owner.webContents.mainFrame
          || new URL(event.senderFrame.url).origin !== DEV_ORIGIN) {
          return { status: 'failed', code: 'FORBIDDEN' }
        }
      } catch {
        return { status: 'failed', code: 'FORBIDDEN' }
      }

      // 先检查类型和字节数，再复制；扩展名筛选不能替代内容检查。
      if (!(bytes instanceof ArrayBuffer)
        || bytes.byteLength < 8
        || bytes.byteLength > 5 * 1024 * 1024) {
        return { status: 'failed', code: 'INVALID_IMAGE' }
      }
      const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
      const png = Buffer.from(new Uint8Array(bytes))
      if (!png.subarray(0, 8).equals(signature)) {
        return { status: 'failed', code: 'INVALID_IMAGE' }
      }
      if (screenshotSaveBusy) {
        return { status: 'failed', code: 'BUSY' }
      }

      screenshotSaveBusy = true
      try {
        const result = await dialog.showSaveDialog(owner, {
          title: '保存截图',
          defaultPath: 'nexus-screenshot.png',
          filters: [{ name: 'PNG 图片', extensions: ['png'] }],
        })
        if (result.canceled) {
          return { status: 'cancelled' }
        }
        if (typeof result.filePath !== 'string' || result.filePath.length === 0) {
          return { status: 'failed', code: 'SAVE_FAILED' }
        }
        await fs.writeFile(result.filePath, png)
        return { status: 'saved' }
      } catch (error) {
        // 不记录异常消息（可能含完整路径）、图片内容或凭据。
        console.warn('[desktop main] Screenshot save failed', error?.code ?? 'UNKNOWN')
        return { status: 'failed', code: 'SAVE_FAILED' }
      } finally {
        screenshotSaveBusy = false
      }
    })

    console.log('[desktop main] Loading existing Vue app')
    await mainWindow.loadURL(DEV_ORIGIN)
    console.log('[desktop main] Document loaded; Vue boots in Renderer')
    if (process.env.NEXUS_DESKTOP_DIAGNOSE === '1') {
      setTimeout(async () => {
        try {
          const state = await mainWindow.webContents.executeJavaScript(`({ path: location.pathname, appLength: document.querySelector('#app')?.innerHTML.length, password: !!document.querySelector('input[type=password]') })`)
          console.log('[desktop diagnose]', JSON.stringify(state), 'visible=', mainWindow.isVisible())
          if (!mainWindow.isVisible()) throw Error('Normal-start window is not visible')
          const image = await mainWindow.webContents.capturePage()
          require('node:fs').writeFileSync(path.join(__dirname, '.runtime', 'normal-start.png'), image.toPNG())
          console.log('[desktop diagnose] Normal-start capture saved')
        } catch (error) { console.error('[desktop diagnose]', error.message) }
      }, 5000)
    }
    if (smoke) {
      // loadURL 完成不等于 Vue 异步路由组件已经挂载；等待真实登录表单。
      for (let attempt = 0; attempt < 100; attempt++) {
        const state = await mainWindow.webContents.executeJavaScript(`({
          ready: location.pathname === '/login' && !!document.querySelector('input[type="password"]') && !!document.querySelector('#app form'),
          noNode: typeof window.require === 'undefined' && typeof window.process === 'undefined'
        })`)
        if (state.ready) {
          if (!state.noNode) throw Error('Renderer unexpectedly exposes Node globals')
          console.log('PASS: existing Vue login mounted; router redirected; Node globals absent')
          app.quit()
          return
        }
        await new Promise(resolve => setTimeout(resolve, 100))
      }
      throw Error('Vue login did not mount within 10 seconds')
    }
  }).catch(error => {
    console.error(`[desktop main] Startup failed: ${error.message}`)
    if (!smoke) dialog.showErrorBox('Nexus 启动失败', '无法加载本地 Vue 页面，请检查开发终端。')
    app.exit(1)
  })
}
app.on('window-all-closed', () => app.quit())
