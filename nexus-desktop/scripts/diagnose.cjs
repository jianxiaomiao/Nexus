const fs = require('node:fs')
const path = require('node:path')
const { _electron } = require('../../nexus-console/node_modules/playwright')
const root = path.resolve(__dirname, '..')
const out = path.join(root, '.runtime', 'diagnostic')
fs.mkdirSync(out, { recursive: true })
;(async () => {
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE
  const { pathToFileURL } = require('node:url')
  const consoleRequire = require('node:module').createRequire(path.resolve(root, '../nexus-console/package.json'))
  const { createServer } = await import(pathToFileURL(consoleRequire.resolve('vite')).href)
  const vite = await createServer({ root: path.resolve(root, '../nexus-console'), configFile: path.resolve(root, '../nexus-console/vite.config.ts'), cacheDir: path.join(root, '.runtime/vite-cache'), server: { host: '127.0.0.1', port: 5178, strictPort: true } })
  await vite.listen()
  try {
  const instance = await _electron.launch({ executablePath: require('electron'), args: [root], env, timeout: 20000 })
  try {
    const page = await instance.firstWindow()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
    await page.waitForURL('**/login')
    await page.locator('input[type=password]').waitFor({ state: 'visible' })
    await page.getByRole('button', { name: '登录', exact: true }).waitFor({ state: 'visible' })
    console.log(JSON.stringify({ url: page.url(), state: await page.evaluate(() => ({ text: document.body.innerText.slice(0, 600), html: document.querySelector('#app')?.innerHTML.length, password: !!document.querySelector('input[type=password]') })), errors }))
    console.log(JSON.stringify(await instance.evaluate(({ app }) => app.getGPUFeatureStatus())))
    await page.screenshot({ timeout: 10000, path: path.join(out, 'desktop-login.png') })
    if (errors.length) throw Error('Page errors captured')
    console.log('PASS: visible login and screenshot; default GPU configuration')
  } finally { await instance.close() }
  } finally { await vite.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
