const { app, BrowserWindow, dialog, session, screen, ipcMain, Notification, protocol, shell } = require('electron')
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

function createIdleTrace() {
  return {
    jobId: null,
    latestJob: null,
    traceStatus: 'IDLE',
    queryFailedInfo: null,
  }
}

function isTrustedScreenshotCaller(event) {
  // 将现有保存处理器里的来源检查提取到这里
  // 合法返回 true；不合法或检查异常返回 false
  try {
    return !(!mainWindow || mainWindow.isDestroyed()
        || event.sender !== mainWindow.webContents
        || !event.senderFrame
        || event.senderFrame !== mainWindow.webContents.mainFrame
        || !isTrustedAppUrl(event.senderFrame.url));
    //new URL(event.senderFrame.url).origin !== DEV_ORIGIN
  }catch (e){
    return false
  }
}

function isTrustedAppUrl(value){
  try{
    const url = new URL(value)
    return url.protocol === 'nexus:'
        && url.host === 'app'
        && url.username === ''
        && url.password === ''
  }catch {
    console.warn("解析url失败")
    return false
  }
}

if(app.isPackaged){

}else{
  // 当前仅实现开发模式，独立构建的页面入口留到下一检查点。
  app.setPath('userData', path.join(__dirname, '.runtime', 'user-data'))
}
  if (process.platform === 'win32') {
    app.setAppUserModelId(process.execPath)
  }
  protocol.registerSchemesAsPrivileged([
    {
      scheme: 'nexus',
      privileges: {
        standard: true,
        secure: true,
        supportFetchAPI: true,
      },
    },
  ])
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

    const notifiedJobIds = new Set()

    let trackingSnapshot = createIdleTrace()
    let activeRun = null

    let screenshotResultBusy = false

    function cancelActiveRun() {
      const previous = activeRun
      activeRun = null

      if (!previous) return

      if (previous.timer !== null) clearTimeout(previous.timer)
      previous.controller?.abort()
      previous.timer = null
      previous.controller = null
      previous.resultController?.abort()
      previous.resultController = null
      previous.apiKey = ''
    }

    function publishTrackingSnapshot() {
      if (!mainWindow || mainWindow.isDestroyed()) return

      mainWindow.webContents.send(
          'screenshot-tracking:updated',
          trackingSnapshot,
      )
    }

    function notifyScreenshotFinished(latestJob){
      if(latestJob.status !== 'SUCCEEDED' && latestJob.status !== 'FAILED') return

      if(notifiedJobIds.has(latestJob.jobId)) return

      notifiedJobIds.add(latestJob.jobId)

      try {
          if(Notification.isSupported()){
            const notification = new Notification({
              title: 'Nexus 截图任务',
              body: latestJob.status === 'SUCCEEDED'
                  ? '截图任务已完成，可返回应用查看。'
                  : '截图任务执行失败，可返回应用查看详情。',
            })

            notification.on('failed', () => {
              console.warn('[desktop main] Screenshot notification failed')
            })

            notification.show()
          }else{
            console.log("截图通知触发,但是由于不支持notification,所以只记录日志")
          }

      }catch (e) {
          console.warn("截图通知异常")
      }
    }

    async function pollScreenshotJob(run) {
      if (activeRun !== run) return

      const controller = new AbortController()
      run.controller = controller

      let timedOut = false

      const requestTimeout = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, 8000)

      try {
        const response = await fetch(
            `http://localhost:8080/v1/web/screenshot-jobs/${encodeURIComponent(run.jobId)}`,
            {
              headers: {
                Authorization: `ApiKey ${run.apiKey}`,
              },
              redirect: 'error',
              signal: controller.signal,
            },
        )
        if (activeRun !== run) return
        if (!response.ok) {
          const message = response.status === 401 ? 'API Key 无效，请重新提供。'
            : response.status === 403 ? 'API Key、所属应用或账号已被禁用。'
              : response.status === 404 ? '任务不存在，或不属于当前 API Key。'
                : '查询任务失败，请稍后重试。'
          trackingSnapshot = { ...trackingSnapshot, traceStatus: 'PAUSED', queryFailedInfo: message }
          publishTrackingSnapshot()
          return
        }

        const contentType = response.headers.get('content-type')

        const mediaType = contentType
            ?.split(';')[0]
            ?.trim()
            .toLowerCase()

        if (mediaType !== 'application/json') throw new Error('INVALID_JOB_RESPONSE')

        const body = await response.json()

        if (activeRun !== run) return

        const job = body?.data
        const nullableString = value => value === null || typeof value === 'string'
        const nullableNumber = value => value === null || (typeof value === 'number' && Number.isFinite(value))
        const valid = body?.code === 'SUCCESS' && job && typeof job === 'object' && !Array.isArray(job)
          && job.jobId === run.jobId
          && ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'].includes(job.status)
          && Number.isSafeInteger(job.attemptCount) && job.attemptCount >= 0
          && typeof job.createdAt === 'string' && typeof job.updatedAt === 'string'
          && nullableNumber(job.resultSizeBytes) && nullableString(job.resultSha256)
          && nullableNumber(job.blockedResources)
          && (job.resourceLimitReached === null || typeof job.resourceLimitReached === 'boolean')
          && nullableString(job.resultExpiresAt) && nullableString(job.failureCode)
          && nullableString(job.startedAt) && nullableString(job.completedAt)
        if (!valid) throw new Error('INVALID_JOB_RESPONSE')

        // 只复制约定字段，不将后端额外字段或 Key 推送给页面。
        const { jobId, status, attemptCount, createdAt, updatedAt, resultSizeBytes,
          resultSha256, blockedResources, resourceLimitReached, resultExpiresAt,
          failureCode, startedAt, completedAt } = job
        const latestJob = { jobId, status, attemptCount, createdAt, updatedAt, resultSizeBytes,
          resultSha256, blockedResources, resourceLimitReached, resultExpiresAt,
          failureCode, startedAt, completedAt }
        const pending = status === 'QUEUED' || status === 'RUNNING'
        trackingSnapshot = { jobId, latestJob, traceStatus: pending ? 'POLLING' : 'TERMINAL', queryFailedInfo: null }
        publishTrackingSnapshot()
        notifyScreenshotFinished(latestJob)
        if (pending && activeRun === run) {
          run.timer = setTimeout(() => {
            run.timer = null
            void pollScreenshotJob(run)
          }, 2000)
        }
      } catch (e) {
        if (activeRun !== run) return
        const message = timedOut ? '查询任务超时，请重试。'
          : e?.message === 'INVALID_JOB_RESPONSE' || e instanceof SyntaxError
            ? '服务器返回的任务状态格式不正确。'
            : '无法连接服务器，请稍后重试。'
        trackingSnapshot = { ...trackingSnapshot, traceStatus: 'PAUSED', queryFailedInfo: message }
        publishTrackingSnapshot()
      } finally {
        clearTimeout(requestTimeout)

        if (run.controller === controller) {
          run.controller = null
        }
      }

    }

    const pagePaths = new Set([
      '/',
      '/login',
      '/register',
      '/applications',
      '/playground',
      '/short-links',
      '/docs',
    ])

    const allowMethods = new Set([
        'GET',
        'POST',
        'PUT',
        'DELETE'
    ])

    const maxRequestBytes = 64 * 1024

    function isPagePath(pathname) {
      if(pagePaths.has(pathname)) return true

      const patterns = [
        /^\/applications\/[^/]+$/,
        /^\/applications\/[^/]+\/keys\/[^/]+$/,
        /^\/docs\/[^/]+$/,
      ]

      return patterns.some(pattern => pattern.test(pathname));
    }

    async function readBody(reader, limit, tooLargeCode, signal) {
      const chunks = []
      let totalBytes = 0
      while (true) {
        if (signal.aborted) throw new Error('Request aborted')
        const { done, value } = await reader.read()
        if (signal.aborted) throw new Error('Request aborted')
        if (done) break
        totalBytes += value.byteLength
        if (totalBytes > limit) {
          const error = new Error('Body too large')
          error.code = tooLargeCode
          throw error
        }
        chunks.push(value)
      }
      const body = new Uint8Array(totalBytes)
      let offset = 0
      for (const chunk of chunks) {
        body.set(chunk, offset)
        offset += chunk.byteLength
      }

      return body
    }

    async function forwardBackend(request, url, policy){
      if(!allowMethods.has(request.method)) return new Response('Not Allowed', { status: 405 })
      const newUrl = new URL('http://localhost:8080')
      newUrl.pathname = url.pathname
      newUrl.search = url.search

      const header = new Headers()
      const allowNames = ['Content-Type', 'Accept', 'Authorization']
      for (const name of allowNames) {
        const value = request.headers.get(name)
        if (value !== null)
          header.set(name, value)
      }
      const controller = new AbortController()

      let stopReason = null

      function onCallerAbort(){
        if(stopReason === null)
          stopReason = 'CALLER_CANCELLED'

        controller.abort()
        if (activeReader) {
          try { Promise.resolve(activeReader.cancel()).catch(() => {}) } catch {}
        }
      }

      let activeReader = null
      let timeout
      const timeoutPromise = new Promise((_, reject) => {
        timeout = setTimeout(() => {
          if(stopReason === null)
            stopReason = 'TIMEOUT'

          controller.abort()
          reject(new Error('Request timed out'))
        }, policy.timeout)
      })

      try {
        request.signal.addEventListener('abort', onCallerAbort, { once: true })

        if (request.signal.aborted) {
          onCallerAbort()
        }

        const operation = async () => {
          let body
          if (request.method !== 'GET' && request.body !== null && !request.signal.aborted) {
            activeReader = request.body.getReader()
            body = await readBody(activeReader, maxRequestBytes, 'REQUEST_TOO_LARGE', controller.signal)
            activeReader = null
          }
          if (controller.signal.aborted) throw new Error('Request aborted')
          const response = await fetch(newUrl, {
            method: request.method,
            headers: header,
            body,
            redirect: 'error',
            signal: controller.signal,
          })
          if (controller.signal.aborted) {
            if (response.body) Promise.resolve(response.body.cancel()).catch(() => {})
            throw new Error('Request aborted')
          }
          const responseHeaders = new Headers({ 'Cache-Control': 'no-store' })

          const responseNeedHeaders = ['X-Nexus-Blocked-Resources'
            , 'X-Nexus-Resource-Limit-Reached', 'Retry-After']

          for(const needHeader of responseNeedHeaders){
            const value = response.headers.get(needHeader)
            if(value !== null)
            responseHeaders.set(needHeader, value)
          }
          const contentType = response.headers.get('Content-Type')
          if (contentType !== null) responseHeaders.set('Content-Type', contentType)

          const noBody = [204, 205, 304].includes(response.status)
          let responseBody = null
          if (!noBody && response.body !== null) {
            activeReader = response.body.getReader()
            responseBody = await readBody(activeReader, policy.responseLimit, 'UPSTREAM_RESPONSE_TOO_LARGE', controller.signal)
            activeReader = null
          }
          return new Response(responseBody, { status: response.status, headers: responseHeaders })
        }
        return await Promise.race([operation(), timeoutPromise])
      } catch (error) {
        const tooLarge = error?.code === 'REQUEST_TOO_LARGE' || error?.code === 'UPSTREAM_RESPONSE_TOO_LARGE'
        let code
        let status
        let message
        if(stopReason === 'TIMEOUT'){
          code = 'TIMEOUT'
          status = 504
          message = 'Request timed out'
        }else if(stopReason === 'CALLER_CANCELLED'){
          code = 'CALLER_CANCELLED'
          // 499 是本地转发层表示调用方取消的非标准约定。
          status = 499
          message = 'Request cancelled by caller'
        }else {
          code = tooLarge ? error.code : 'UPSTREAM_UNAVAILABLE'
          status = error?.code === 'REQUEST_TOO_LARGE' ? 413 : 502
          message = tooLarge ? 'Body too large' : 'Backend request failed'
        }
        return Response.json(
            { code, message },
            { status, headers: { 'Cache-Control': 'no-store' } },
        )
      } finally {
        clearTimeout(timeout)
        controller.abort()
        request.signal.removeEventListener('abort', onCallerAbort)
        // 取消读取仅作清理，不等待可能永不结束的 cancel Promise。
        if (activeReader) {
          try { Promise.resolve(activeReader.cancel()).catch(() => {}) } catch {}
        }
      }
    }

    protocol.handle('nexus', async (request) => {
      const url = new URL(request.url)

      if (!isTrustedAppUrl(url) ) {
        return new Response('Not Found', { status: 404 })
      }
      if (url.pathname.startsWith('/api/')){
        return  forwardBackend(request, url, { timeout: 8 * 1000, responseLimit: 1 * 1024 *1024})
      }
      else if(url.pathname.startsWith('/v1/')){
        return  forwardBackend(request, url, { timeout: 30 * 1000, responseLimit: 8 * 1024 *1024})
      }
      else if(request.method !== 'GET'){
        return new Response('Not Found', { status: 404 })
      }


      const rendererDir = path.join(__dirname, 'dist', 'renderer')
      let filePath
      let contentType

      if (isPagePath(url.pathname)) {
        filePath = path.join(rendererDir, 'index.html')
        contentType = 'text/html; charset=utf-8'
      } else {
        // The current Vite build places assets directly in this directory.
        const match = /^\/assets\/([A-Za-z0-9_-]+\.(js|css|png))$/.exec(url.pathname)
        if (!match) return new Response('Not Found', { status: 404 })
        contentType = { js: 'text/javascript; charset=utf-8', css: 'text/css; charset=utf-8', png: 'image/png' }[match[2]]

        const root = path.resolve(__dirname, 'dist', 'renderer')

        let pathname
        try {
          pathname = decodeURIComponent(url.pathname)
        } catch {
          return new Response('Bad Request', { status: 400 })
        }

        // 先限制请求范围，拒绝 Windows 路径分隔符和冒号。
        if (
            !pathname.startsWith('/assets/') ||
            /[\\:\0]/.test(pathname)
        ) {
          return new Response('Forbidden', { status: 403 })
        }

        // 去掉开头的 /，再以固定根目录解析目标文件。
        const target = path.resolve(root, pathname.slice(1))
        const alongPath = path.relative(root, target)

        if (
            alongPath === '..' ||
            alongPath.startsWith(`..${path.sep}`) ||
            path.isAbsolute(alongPath)
        ) {
          return new Response('Forbidden', { status: 403 })
        }

        filePath = target
      }

      try {
        const contents = await fs.readFile(filePath)
        return new Response(contents, { headers: { 'Content-Type': contentType } })
      } catch (error) {
        if (error.code === 'ENOENT' || error.code === 'ENOTDIR') {
          return new Response('Not Found', { status: 404 })
        }
        console.error('[desktop main] Failed to read renderer file:', error.code)
        return new Response('Internal Server Error', { status: 500 })
      }
    })

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
      //if (new URL(url).origin !== DEV_ORIGIN) event.preventDefault()
      if(!isTrustedAppUrl(url)) event.preventDefault()
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

    ipcMain.handle('short-link:open', async (event, shortCode) => {
      if (!isTrustedScreenshotCaller(event)) {
        return { status: 'failed', code: 'FORBIDDEN' }
      }
      // 显式长度检查也拒绝末尾换行；只接受后端生成的12位ASCII短码。
      if (typeof shortCode !== 'string' || shortCode.length !== 12 || !/^[0-9A-Za-z]{12}$/.test(shortCode)) {
        return { status: 'failed', code: 'INVALID_REQUEST' }
      }

      const target = new URL(`/s/${shortCode}`, 'http://localhost:8080').href
      try {
        await shell.openExternal(target)
        // 仅表示交给系统处理，不证明短链有效或目标页面已加载。
        return { status: 'opened' }
      } catch {
        return { status: 'failed', code: 'OPEN_FAILED' }
      }
    })

    let screenshotSaveBusy = false

    ipcMain.handle('screenshot:save', async (event, bytes) => {
      // 窗口、网页内容、主框架和来源必须全部匹配；失效的框架也拒绝。
      const owner = mainWindow
      try {
        if (!isTrustedScreenshotCaller(event)) {
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

    ipcMain.handle('screenshot-tracking:start', (event, input) => {
      if (!isTrustedScreenshotCaller(event)) {
        return { status: 'failed', code: 'FORBIDDEN' }
      }

      if (
          input == null ||
          typeof input !== 'object' ||
          Array.isArray(input) ||
          typeof input.jobId !== 'string' ||
          typeof input.apiKey !== 'string'
      ) {
        return { status: 'failed', code: 'INVALID_REQUEST' }
      }

      const jobIdPattern =
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

      const apiKeyPattern =
          /^nxk_v1_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[A-Za-z0-9_-]{43}$/

      if (!jobIdPattern.test(input.jobId) || !apiKeyPattern.test(input.apiKey)) {
        return { status: 'failed', code: 'INVALID_REQUEST' }
      }

      if (trackingSnapshot.jobId !== null &&
          trackingSnapshot.jobId !== input.jobId) {
        return { status: 'failed', code: 'BUSY' }
      }else{
        if(trackingSnapshot.traceStatus === 'POLLING'|| trackingSnapshot.traceStatus === 'TERMINAL'){
          return {
            status: 'ok',
            snapshot: trackingSnapshot,
          }
        }else if(trackingSnapshot.traceStatus === 'PAUSED' || trackingSnapshot.traceStatus === 'IDLE'){
          const latestJob = trackingSnapshot.latestJob
          cancelActiveRun()
          const run = {
            jobId: input.jobId,
            apiKey: input.apiKey,
            controller: null,
            resultController: null,
            timer: null,
          }

          activeRun = run

          trackingSnapshot = { jobId: input.jobId, latestJob, traceStatus: 'POLLING', queryFailedInfo: null }

          publishTrackingSnapshot()

          void pollScreenshotJob(run)

          return {
            status: 'ok',
            snapshot: trackingSnapshot,
          }
        }
      }
    })

    ipcMain.handle('screenshot-tracking:read', (event) => {
      if (!isTrustedScreenshotCaller(event)) {
        return { status: 'failed', code: 'FORBIDDEN' }
      }

      return {
        status: 'ok',
        snapshot: trackingSnapshot,
      }
    })

    ipcMain.handle('screenshot-tracking:stop', (event) => {
      // ① 检查来源，不合法返回 FORBIDDEN
      if (!isTrustedScreenshotCaller(event)) {
        return { status: 'failed', code: 'FORBIDDEN' }
      }

      cancelActiveRun()

      // ② 将 trackingSnapshot 重置为新的 IDLE 快照
      trackingSnapshot = createIdleTrace()
      // ③ 向当前窗口发送 screenshot-tracking:updated
      publishTrackingSnapshot()
      // ④ 返回 { status: 'ok', snapshot: trackingSnapshot }
      return { status: 'ok', snapshot: trackingSnapshot }
    })

    ipcMain.handle('screenshot-result:get', async (event, jobId) =>{
      if (!isTrustedScreenshotCaller(event)) {
        return { status: 'failed', code: 'FORBIDDEN' }
      }
      const jobIdPattern =
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

      if(typeof jobId !== 'string' || !jobIdPattern.test(jobId)){
        return  { status: 'failed', code: 'INVALID_REQUEST' }
      }

      if(activeRun === null || activeRun.jobId !== jobId){
        return  { status: 'failed', code: 'STALE_TASK' }
      }

      if (trackingSnapshot.latestJob?.status !== 'SUCCEEDED') {
        return { status: 'failed', code: 'NOT_READY' }
      }

      if (screenshotResultBusy) {
        return { status: 'failed', code: 'BUSY' }
      }

      screenshotResultBusy = true

      const run = activeRun
      const controller = new AbortController()
      run.resultController = controller

      const timeout = setTimeout(() => {
        controller.abort()
      }, 8000)

      try {
        const response = await fetch(
            `http://localhost:8080/v1/web/screenshot-jobs/${encodeURIComponent(jobId)}/result`,
            {
              headers: { Authorization: `ApiKey ${run.apiKey}` },
              redirect: 'error',
              signal: controller.signal,
            },
        )

        if (activeRun !== run) {
          return { status: 'failed', code: 'STALE_TASK' }
        }

        if (!response.ok) {
          return { status: 'failed', code: 'FETCH_FAILED' }
        }

        const mediaType = response.headers.get('content-type')
            ?.split(';')[0]
            ?.trim()
            .toLowerCase()

        if (mediaType !== 'image/png' || !response.body) {
          return { status: 'failed', code: 'INVALID_IMAGE' }
        }

        const maxBytes = 5 * 1024 * 1024
        const reader = response.body.getReader()
        const chunks = []
        let totalBytes = 0

        while (true) {
          const { done, value } = await reader.read()

          if (activeRun !== run) {
            return { status: 'failed', code: 'STALE_TASK' }
          }

          if (done) break

          totalBytes += value.byteLength
          if (totalBytes > maxBytes) {
            return { status: 'failed', code: 'INVALID_IMAGE' }
          }

          chunks.push(value)
        }

        if (totalBytes < 8) {
          return { status: 'failed', code: 'INVALID_IMAGE' }
        }

        const bytes = new Uint8Array(totalBytes)
        let offset = 0

        for (const chunk of chunks) {
          bytes.set(chunk, offset)
          offset += chunk.byteLength
        }

        const signature = [137, 80, 78, 71, 13, 10, 26, 10]
        const validSignature = signature.every((byte, index) => bytes[index] === byte)

        if (!validSignature) {
          return { status: 'failed', code: 'INVALID_IMAGE' }
        }

        if (activeRun !== run) {
          return { status: 'failed', code: 'STALE_TASK' }
        }

        return {
          status: 'ok',
          jobId,
          bytes: bytes.buffer,
          cacheControl: response.headers.get('cache-control'),
        }
      } catch {
        if (activeRun !== run) {
          return { status: 'failed', code: 'STALE_TASK' }
        }
        return { status: 'failed', code: 'FETCH_FAILED' }
      } finally {
        clearTimeout(timeout)
        controller.abort()
        if (run.resultController === controller) {
          run.resultController = null
        }
        screenshotResultBusy = false
      }
    })

    console.log('[desktop main] Loading existing Vue app')
    // await mainWindow.loadURL(DEV_ORIGIN)
    await mainWindow.loadURL('nexus://app/')
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
app.on('window-all-closed', () => app.quit())
