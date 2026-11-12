const { app, BrowserWindow } = require('electron')
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { deflateSync, crc32 } = require('node:zlib')
const { createGateway } = require('./gateway.cjs')
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
// 构造带正确块 CRC 的 1x1 RGBA PNG，避免只验证签名而漏掉损坏样本。
const chunk = (type, data) => {
  const name = Buffer.from(type); const size = Buffer.alloc(4); size.writeUInt32BE(data.length)
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([name, data])))
  return Buffer.concat([size, name, data, crc])
}
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4); ihdr[8] = 8; ihdr[9] = 6
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(Buffer.from([0, 255, 0, 0, 255]))), chunk('IEND', Buffer.alloc(0))])
app.whenReady().then(async () => {
  let preflights = 0, directGets = 0, unexpected = 0
  const hits = new Map()
  const upstream = http.createServer(async (req, res) => {
    if (req.method === 'OPTIONS') { preflights++; return res.writeHead(401).end() }
    if (req.headers.origin) directGets++
    hits.set(req.url, (hits.get(req.url) || 0) + 1)
    const json = (status, value) => res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(value))
    if (req.url === '/api/login') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk)
      return json(201, { body: JSON.parse(Buffer.concat(chunks).toString()), method: req.method, contentType: req.headers['content-type'], authorization: req.headers.authorization || null, cookie: req.headers.cookie || null })
    }
    if (req.url === '/api/check') return req.headers.authorization === 'Bearer test-only' ? json(200, { identity: 'human' }) : json(401, { code: 'UNAUTHORIZED' })
    if (req.url === '/v1/image') {
      if (req.headers.authorization !== 'ApiKey test-only') return json(401, { code: 'UNAUTHORIZED' })
      return res.writeHead(200, { 'content-type': 'image/png', 'content-length': png.length, 'cache-control': 'no-store', 'content-disposition': 'inline; filename="test.png"' }).end(png)
    }
    if (req.url === '/api/slow') return
    if (req.url === '/api/drop') return req.socket.destroy()
    if (req.url === '/api/redirect') return res.writeHead(302, { location: '/redirect-target' }).end()
    if (req.url === '/api/truncated') { res.writeHead(200, { 'content-length': '100' }); res.write('short'); return setTimeout(() => res.destroy(), 30) }
    unexpected++; return json(404, {})
  })
  const upstreamPort = await listen(upstream)
  const gateway = createGateway(upstreamPort, fs.readFileSync(path.join(__dirname, 'renderer-test.js'), 'utf8'), png.toString('base64'))
  const port = await listen(gateway)
  const window = new BrowserWindow({ show: false, webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true } })
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.on('console-message', details => { if (details.message.startsWith('PASS:') || details.level === 'error') console.log(details.message) })
  let done = false
  const finish = ok => {
    if (done) return; done = true
    console.log(ok ? 'PASS: controlled forwarding experiment' : 'FAIL: controlled forwarding experiment')
    gateway.closeAllConnections(); upstream.closeAllConnections(); gateway.close(); upstream.close(); app.exit(ok ? 0 : 1)
  }
  const raw = (requestPath, headers = {}, method = 'GET') => new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path: requestPath, method, headers }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)) })
    req.on('error', reject); req.end()
  })
  window.on('page-title-updated', async (_, title) => {
    if (!['PASS', 'FAIL'].includes(title)) return
    if (title === 'FAIL') return finish(false)
    try {
      assert.equal(await raw('http://example.invalid/api/check'), 404)
      assert.equal(await raw('/api/check', { host: 'example.invalid' }), 403)
      assert.equal(await raw('/api/check', { origin: 'https://example.invalid' }), 403)
      assert.equal(await raw('/api/check', { 'sec-fetch-site': 'cross-site' }), 403)
      assert.equal(await raw('/api/%63heck'), 404)
      assert.equal(await raw('/api/check?target=http://example.invalid'), 404)
      assert.equal(await raw('/api/check', {}, 'DELETE'), 405)
      assert.equal(preflights, 1); assert.equal(directGets, 0); assert.equal(unexpected, 0)
      assert.equal(hits.get('/api/login'), 1)
      console.log('PASS: 7 raw HTTP rejection checks and upstream counters')
      finish(true)
    } catch (error) { console.error(error.message); finish(false) }
  })
  setTimeout(() => finish(false), 20000).unref()
  await window.loadURL(`http://127.0.0.1:${port}/`)
}).catch(error => { console.error(error.message); app.exit(1) })
