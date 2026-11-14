import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const desktopRoot = fileURLToPath(new URL('..', import.meta.url))
const consoleRoot = path.resolve(desktopRoot, '../nexus-console')
const consoleRequire = createRequire(path.join(consoleRoot, 'package.json'))
const outDir = path.join(desktopRoot, 'dist', 'renderer')

try {
  // 复用 Vue 源码及其类型配置；类型错误时不继续生成新的构建产物。
  const check = spawnSync(process.execPath, [
    consoleRequire.resolve('vue-tsc/bin/vue-tsc.js'), '--build',
  ], { cwd: consoleRoot, stdio: 'inherit' })
  if (check.error) throw check.error
  if (check.status !== 0) throw new Error('Vue type check failed')

  const { build } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
  await build({
    root: consoleRoot,
    configFile: path.join(consoleRoot, 'vite.config.ts'),
    // 标准 nexus://app/ 协议从根路径提供资源，并保留 History 路由。
    base: '/',
    // 仅桌面产物使用本地公开短地址，避免复制出 nexus://app/s/...。
    define: {
      'import.meta.env.VITE_SHORT_LINK_PUBLIC_BASE_URL': JSON.stringify('http://localhost:8080'),
    },
    build: {
      outDir,
      // 仅清空固定的桌面 renderer 产物目录，不覆盖 Web 的 dist。
      emptyOutDir: true,
    },
  })
  console.log('[desktop build] Renderer ready: dist/renderer/index.html')
} catch (error) {
  console.error(`[desktop build] Failed: ${error.message}`)
  process.exitCode = 1
}
