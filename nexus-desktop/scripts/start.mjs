import { access } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { spawn } from 'node:child_process'

const desktopRoot = fileURLToPath(new URL('..', import.meta.url))
const desktopRequire = createRequire(path.join(desktopRoot, 'package.json'))

try {
  await access(path.join(desktopRoot, 'dist', 'renderer', 'index.html'))
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  env.NEXUS_DESKTOP_SMOKE = process.argv.includes('--smoke') ? '1' : '0'
  env.NEXUS_DESKTOP_DIAGNOSE = '0'

  // 只启动 Electron；构建产物由 Main 的 nexus 协议提供，不启动 Vite。
  const entry = env.NEXUS_DESKTOP_SMOKE === '1'
    ? path.join(desktopRoot, 'scripts', 'standalone-smoke-main.cjs')
    : desktopRoot
  const child = spawn(desktopRequire('electron'), [entry], {
    cwd: desktopRoot,
    env,
    stdio: 'inherit',
  })
  child.on('error', error => {
    console.error(`[desktop start] Failed: ${error.message}`)
    process.exitCode = 1
  })
  child.on('exit', code => { process.exitCode = code ?? 1 })
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      if (child.exitCode === null) child.kill()
    })
  }
} catch (error) {
  console.error(error.code === 'ENOENT'
    ? '[desktop start] Renderer not built. Run npm run build:renderer first.'
    : `[desktop start] Failed: ${error.message}`)
  process.exitCode = 1
}
