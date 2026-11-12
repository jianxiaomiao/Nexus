const http = require('node:http')

// 教学原型：固定上游、精确路径。页面不能提供转发目标。
exports.createGateway = (upstreamPort, script, expectedPng) => http.createServer(async (req, res) => {
  const origin = `http://127.0.0.1:${req.socket.localPort}`
  const fail = (status, code) => {
    if (res.destroyed) return
    if (res.headersSent) return res.destroy()
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify({ code }))
  }
  if (req.headers.host !== origin.slice(7) || (req.headers.origin && req.headers.origin !== origin) || req.headers['sec-fetch-site'] === 'cross-site') return fail(403, 'SOURCE_DENIED')
  if (req.method === 'GET' && req.url === '/') {
    // 只为 CORS 对照放行固定上游的 CSP 连接检查。
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'content-security-policy': `default-src 'self'; script-src 'self'; connect-src 'self' http://127.0.0.1:${upstreamPort}` })
    return res.end('<!doctype html><title>Nexus experiment</title><script src="/test.js"></script>')
  }
  if (req.method === 'GET' && req.url === '/test.js') return res.writeHead(200, { 'content-type': 'text/javascript' }).end(`const upstreamPort=${upstreamPort}; const expectedPng=${JSON.stringify(expectedPng)};\n${script}`)
  const routes = { '/api/login': 'POST', '/api/check': 'GET', '/v1/image': 'GET', '/api/slow': 'GET', '/api/drop': 'GET', '/api/redirect': 'GET', '/api/truncated': 'GET' }
  if (!Object.hasOwn(routes, req.url)) return fail(404, 'PATH_DENIED')
  if (req.method !== routes[req.url]) return fail(405, 'METHOD_DENIED')
  const headers = {}
  let body = Buffer.alloc(0)
  if (req.method === 'POST') {
    if (req.headers['content-type'] !== 'application/json') return fail(415, 'JSON_REQUIRED')
    const chunks = []; let size = 0
    try {
      for await (const chunk of req) {
        size += chunk.length
        if (size > 4096) { fail(413, 'BODY_TOO_LARGE'); return }
        chunks.push(chunk)
      }
      body = Buffer.concat(chunks); JSON.parse(body.toString('utf8'))
    } catch { return fail(400, 'INVALID_JSON') }
    headers['content-type'] = 'application/json'; headers['content-length'] = body.length
  }
  // 登录不继承会话；其他路径只转交显式凭据，不替换其类型。
  if (req.url !== '/api/login' && req.headers.authorization) headers.authorization = req.headers.authorization
  const forward = http.request({ host: '127.0.0.1', port: upstreamPort, path: req.url, method: req.method, headers }, upstream => {
    if (upstream.statusCode >= 300 && upstream.statusCode < 400) { upstream.resume(); return fail(502, 'UPSTREAM_REDIRECT_BLOCKED') }
    const out = {}
    for (const name of ['content-type', 'content-length', 'cache-control', 'content-disposition']) if (upstream.headers[name]) out[name] = upstream.headers[name]
    res.writeHead(upstream.statusCode, out)
    upstream.on('aborted', () => res.destroy())
    upstream.on('error', () => res.destroy())
    upstream.pipe(res)
  })
  const deadline = setTimeout(() => { fail(504, 'UPSTREAM_TIMEOUT'); forward.destroy() }, 1500)
  forward.on('error', () => fail(502, 'UPSTREAM_UNAVAILABLE'))
  res.on('close', () => { clearTimeout(deadline); forward.destroy() })
  forward.end(body)
})
