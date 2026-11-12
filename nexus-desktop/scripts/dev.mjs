import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import { spawn } from 'node:child_process'

const desktopRoot = fileURLToPath(new URL('..', import.meta.url))
const consoleRoot = path.resolve(desktopRoot, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const desktopRequire = createRequire(path.join(desktopRoot, 'package.json'))
let server
let child
let stopping = false

async function stop(code) {
  if (stopping) return
  stopping = true
  if (child && child.exitCode === null) child.kill()
  await server?.close()
  process.exitCode = code
}

try {
  // 复用现有 Vite 配置（包括 /api、/v1、/s 代理），不复制 Vue 项目。
  const { createServer } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
  server = await createServer({
    root: consoleRoot,
    configFile: path.join(consoleRoot, 'vite.config.ts'),
    cacheDir: path.join(desktopRoot, '.runtime', 'vite-cache'),
    server: { host: '127.0.0.1', port: 5178, strictPort: true, open: false },
  })
  await server.listen()
  console.log('[desktop dev] Vite ready: http://127.0.0.1:5178')
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  env.NEXUS_DESKTOP_SMOKE = process.argv.includes('--smoke') ? '1' : '0'
  env.NEXUS_DESKTOP_DIAGNOSE = process.argv.includes('--diagnose') ? '1' : '0'
  child = spawn(desktopRequire('electron'), [desktopRoot], { cwd: desktopRoot, env, stdio: 'inherit' })
  child.on('error', error => { console.error(error.message); void stop(1) })
  child.on('exit', code => { void stop(code ?? 1) })
  process.on('SIGINT', () => { void stop(0) })
  process.on('SIGTERM', () => { void stop(0) })
} catch (error) {
  console.error(`[desktop dev] Startup failed: ${error.message}`)
  await stop(1)
}
