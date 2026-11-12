;(async () => {
  let checks = 0
  const check = (ok, name) => { if (!ok) throw Error(name); checks++ }
  const human = await fetch('/api/check', { headers: { Authorization: 'Bearer test-only' } })
  check(human.status === 200 && (await human.json()).identity === 'human', 'Bearer')
  const invalid = await fetch('/api/check')
  check(invalid.status === 401 && (await invalid.json()).code === 'UNAUTHORIZED', '401 JSON')
  check((await fetch('/api/check', { headers: { Authorization: 'ApiKey test-only' } })).status === 401, 'credential types stay distinct')
  const login = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer test-only' }, body: JSON.stringify({ email: 'test@example.invalid', password: '模拟密码' }) })
  const data = await login.json()
  check(login.status === 201 && data.method === 'POST' && data.contentType === 'application/json' && data.body.password === '模拟密码' && data.authorization === null && data.cookie === null, 'POST UTF-8 JSON')
  check((await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '{}' })).status === 415, 'content type')
  check((await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{' })).status === 400, 'malformed JSON')
  check((await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value: 'x'.repeat(5000) }) })).status === 413, 'body limit')
  const image = await fetch('/v1/image', { headers: { Authorization: 'ApiKey test-only' } })
  const bytes = new Uint8Array(await image.arrayBuffer())
  check(image.status === 200 && btoa(String.fromCharCode(...bytes)) === expectedPng, 'complete PNG bytes')
  const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }))
  check(bitmap.width === 1 && bitmap.height === 1, 'PNG decodes'); bitmap.close()
  check(image.headers.get('Content-Type') === 'image/png' && image.headers.get('Cache-Control') === 'no-store' && image.headers.get('Content-Disposition').includes('test.png'), 'PNG headers')
  const slow = await fetch('/api/slow'); check(slow.status === 504 && (await slow.json()).code === 'UPSTREAM_TIMEOUT', 'deadline')
  const drop = await fetch('/api/drop'); check(drop.status === 502 && (await drop.json()).code === 'UPSTREAM_UNAVAILABLE', 'disconnect')
  const redirect = await fetch('/api/redirect'); check(redirect.status === 502 && (await redirect.json()).code === 'UPSTREAM_REDIRECT_BLOCKED', 'redirect')
  let truncated = false
  try { await (await fetch('/api/truncated')).arrayBuffer() } catch { truncated = true }
  check(truncated, 'truncated response rejects')
  check((await fetch('/other')).status === 404, 'path rejection')
  let blocked = false
  try { await fetch(`http://127.0.0.1:${upstreamPort}/api/check`, { headers: { Authorization: 'Bearer test-only' } }) } catch { blocked = true }
  check(blocked, 'direct cross-origin blocked')
  console.log(`PASS: ${checks} renderer checks`)
  document.title = 'PASS'
})().catch(error => { console.error(error.message); document.title = 'FAIL' })
