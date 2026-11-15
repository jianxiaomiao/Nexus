// Standalone acceptance fixture. Never imported by Main or included in the ASAR.
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const readline = require('node:readline')
const assert = require('node:assert/strict')
const vm = require('node:vm')

const runtime = path.resolve(__dirname, '../.runtime')
const ids = {
  success: '70000000-0000-4000-8000-000000000001',
  failure: '70000000-0000-4000-8000-000000000002',
}
// Deliberately fabricated credential; no real credential is read or recorded.
const fakeKey = `nxk_v1_00000000-0000-4000-8000-000000000000_${'A'.repeat(43)}`

async function startMock(port, record = () => {}) {
  const states = { success: 'RUNNING', failure: 'RUNNING' }
  const createdAt = new Date().toISOString()
  const requests = []
  const servers = []
  let actualPort = port
  const handler = (req, res) => {
    const fixture = Object.keys(ids).find(name => req.url === `/v1/web/screenshot-jobs/${ids[name]}`)
    const host = req.headers.host
    const validHost = [`localhost:${actualPort}`, `127.0.0.1:${actualPort}`, `[::1]:${actualPort}`].includes(host)
    const authorized = req.headers.authorization === `ApiKey ${fakeKey}`
    let status = 200
    let reason = 'fixture'
    if (!validHost) { status = 400; reason = 'host-rejected' }
    else if (req.method !== 'GET') { status = 405; reason = 'method-rejected' }
    else if (!fixture) { status = 404; reason = 'route-rejected' }
    else if (!authorized) { status = 401; reason = 'credential-rejected' }
    else if (req.headers['transfer-encoding'] || (req.headers['content-length'] && req.headers['content-length'] !== '0')) {
      status = 400; reason = 'body-rejected'
    }
    req.resume()
    const event = {
      kind: 'request', at: new Date().toISOString(),
      method: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'HEAD'].includes(req.method) ? req.method : 'OTHER',
      fixture: fixture ?? null, httpStatus: status,
      jobStatus: status === 200 ? states[fixture] : null, reason,
    }
    // Do not retain raw URLs, request bodies or headers.
    requests.push(event)
    record(event)
    res.setHeader('Content-Type', 'application/json; charset=utf-8')
    res.setHeader('Cache-Control', 'no-store')
    if (status === 405) res.setHeader('Allow', 'GET')
    res.statusCode = status
    if (status !== 200) { res.end(JSON.stringify({ code: 'MOCK_REQUEST_REJECTED' })); return }
    const jobStatus = states[fixture]
    const terminal = jobStatus !== 'RUNNING'
    const succeeded = jobStatus === 'SUCCEEDED'
    res.end(JSON.stringify({ code: 'SUCCESS', data: {
      jobId: ids[fixture], status: jobStatus, attemptCount: 1,
      createdAt, updatedAt: event.at, startedAt: createdAt,
      completedAt: terminal ? event.at : null,
      resultSizeBytes: succeeded ? 134 : null,
      resultSha256: succeeded ? '0'.repeat(64) : null,
      blockedResources: succeeded ? 0 : null,
      resourceLimitReached: succeeded ? false : null,
      resultExpiresAt: succeeded ? new Date(Date.now() + 3600000).toISOString() : null,
      failureCode: jobStatus === 'FAILED' ? 'CONTROLLED_ACCEPTANCE_FAILURE' : null,
    } }))
  }
  try {
    // Require both loopback families; never listen on a wildcard or choose a new live port.
    for (const host of ['127.0.0.1', '::1']) {
      const server = http.createServer(handler)
      server.requestTimeout = 5000
      server.headersTimeout = 5000
      server.maxHeadersCount = 24
      servers.push(server)
      await new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen({ host, port: actualPort, ipv6Only: host === '::1', exclusive: true }, () => {
          server.removeListener('error', reject)
          resolve()
        })
      })
      actualPort = server.address().port
    }
  } catch (error) {
    for (const server of servers) { server.close(); server.closeAllConnections() }
    throw error
  }
  return {
    port: actualPort, states, requests,
    complete(name) {
      assert.ok(Object.hasOwn(ids, name), 'Unknown fixture')
      states[name] = name === 'success' ? 'SUCCEEDED' : 'FAILED'
      record({ kind: 'control', at: new Date().toISOString(), fixture: name, jobStatus: states[name] })
    },
    async close() {
      await Promise.all(servers.map(server => new Promise(resolve => {
        server.close(resolve)
        server.closeAllConnections()
      })))
    },
  }
}

function snippets() {
  const shared = `
  if (location.protocol !== 'nexus:' || location.host !== 'app') throw Error('Use the installed Nexus main page');
  const bridge = window.nexusDesktop;
  if (!bridge) throw Error('Desktop bridge missing');
  const successId = ${JSON.stringify(ids.success)};
  const failureId = ${JSON.stringify(ids.failure)};
  const apiKey = 'nxk_v1_00000000-0000-4000-8000-000000000000_' + 'A'.repeat(43);
  const read = async () => {
    const result = await bridge.readScreenshotTracking();
    if (result.status !== 'ok') throw Error('Cannot read tracking snapshot');
    return result.snapshot;
  };
  const report = snapshot => console.log(JSON.stringify({
    jobId: snapshot.jobId, traceStatus: snapshot.traceStatus,
    jobStatus: snapshot.latestJob?.status ?? null, queryFailedInfo: snapshot.queryFailedInfo,
  }));
  const start = async jobId => {
    const result = await bridge.startScreenshotTracking({ jobId, apiKey });
    if (result.status !== 'ok') throw Error('Tracking start rejected: ' + result.code);
  };
  const waitFor = async (jobId, status) => {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const snapshot = await read();
      if (snapshot.jobId === jobId && snapshot.latestJob?.status === status) { report(snapshot); return; }
      if (snapshot.traceStatus === 'PAUSED') { report(snapshot); throw Error('Tracking paused; check mock service'); }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw Error('Expected fixture state not observed within 5 seconds');
  };
  const stop = async () => {
    const result = await bridge.stopScreenshotTracking();
    if (result.status !== 'ok') throw Error('Tracking stop rejected');
  };
`
  const wrap = code => `(async () => {${shared}\n${code}\n})().catch(error => console.error(error.message));\n`
  return {
    '01-start-success.js': wrap(`  const before = await read();
  if (before.jobId !== null) throw Error('Existing tracking found; stop and investigate before testing');
  await start(successId);
  await waitFor(successId, 'RUNNING');`),
    '02-read-state.js': wrap('  report(await read());'),
    '03-retrack-success.js': wrap(`  const before = await read();
  if (before.jobId !== successId || before.latestJob?.status !== 'SUCCEEDED' || before.traceStatus !== 'TERMINAL')
    throw Error('Finish the success fixture first');
  await stop();
  await start(successId);
  await waitFor(successId, 'SUCCEEDED');`),
    '04-start-failure.js': wrap(`  const before = await read();
  if (before.jobId !== successId || before.latestJob?.status !== 'SUCCEEDED' || before.traceStatus !== 'TERMINAL')
    throw Error('Finish the success and dedup checks first');
  await stop();
  await start(failureId);
  await waitFor(failureId, 'RUNNING');`),
    '05-cleanup.js': wrap(`  const before = await read();
  if (before.jobId !== null && ![successId, failureId].includes(before.jobId))
    throw Error('Unrelated tracking found; refusing to clear it');
  await stop();
  report(await read());`),
  }
}

function checkedDir(value) {
  const dir = path.resolve(value)
  const relative = path.relative(runtime, dir)
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Evidence directory must be a child of .runtime')
  return dir
}

function prepare() {
  fs.mkdirSync(runtime, { recursive: true })
  const dir = fs.mkdtempSync(path.join(runtime, 'installed-notification-mock-'))
  for (const [name, source] of Object.entries(snippets())) fs.writeFileSync(path.join(dir, name), source)
  fs.writeFileSync(path.join(dir, 'preparation.json'), JSON.stringify({
    preparedAt: new Date().toISOString(), fixtures: ids,
    installedCodeModified: false, liveAcceptance: 'not-started',
    instructions: 'See nexus-desktop/README.md: installed notification acceptance',
  }, null, 2))
  console.log(`Prepared: ${dir}`)
  console.log(`After the user stops the real backend: node scripts/serve-installed-notification-mock.cjs --serve --dir "${dir}"`)
}

async function serve(dir) {
  checkedDir(dir)
  if (!fs.existsSync(path.join(dir, 'preparation.json'))) throw Error('Run --prepare first')
  const eventsFile = path.join(dir, 'requests.jsonl')
  if (fs.existsSync(eventsFile)) throw Error('This evidence directory has already been used; prepare a new one')
  let mock
  const events = []
  const record = event => {
    events.push(event)
    fs.appendFileSync(eventsFile, JSON.stringify(event) + '\n')
  }
  try { mock = await startMock(8080, record) }
  catch (error) { throw Error(`Mock not started (${error.code ?? 'BIND_FAILED'}). Both loopback addresses must be free on 8080; no process was stopped.`) }
  record({ kind: 'ready', at: new Date().toISOString(), port: 8080, hosts: ['127.0.0.1', '::1'] })
  let stopping = false
  const report = () => {
    const summary = {
      updatedAt: new Date().toISOString(), states: mock.states,
      responses: mock.requests,
      taskCreationAccepted: false, pngEndpointProvided: false,
      userVisibleNotification: 'requires-user-confirmation', passed: null,
    }
    fs.writeFileSync(path.join(dir, 'service-summary.json'), JSON.stringify(summary, null, 2))
    console.log(JSON.stringify({ states: mock.states, requests: mock.requests.length }))
  }
  const input = readline.createInterface({ input: process.stdin })
  const stop = async () => {
    if (stopping) return
    stopping = true
    input.close()
    await mock.close()
    record({ kind: 'stopped', at: new Date().toISOString() })
    report()
    console.log('STOPPED: both loopback listeners released; the real backend may be restarted.')
  }
  input.on('line', line => {
    const command = line.trim()
    if (command === 'success') mock.complete('success')
    else if (command === 'fail') mock.complete('failure')
    else if (command === 'stop') { void stop(); return }
    else if (command !== 'report') { console.log('Commands: success | fail | report | stop'); return }
    report()
  })
  input.on('close', () => { if (!stopping) void stop() })
  process.once('SIGINT', () => { void stop() })
  process.once('SIGTERM', () => { void stop() })
  console.log(`READY: loopback-only localhost:8080; both fixtures RUNNING. Evidence: ${dir}`)
  console.log('Commands: success | fail | report | stop. No HTTP control or creation endpoint exists.')
  report()
}

async function selfTest() {
  const mock = await startMock(0)
  let conflictProbe
  try {
    const url = `http://127.0.0.1:${mock.port}/v1/web/screenshot-jobs/${ids.success}`
    const headers = { Authorization: `ApiKey ${fakeKey}` }
    const get = (address, options = {}) => fetch(address, { ...options, signal: AbortSignal.timeout(3000) })
    const rawGet = extraHeaders => new Promise((resolve, reject) => {
      const request = http.request(url, { method: 'GET', headers: { ...headers, ...extraHeaders } }, response => {
        response.resume()
        response.once('end', () => resolve(response.statusCode))
      })
      request.setTimeout(3000, () => request.destroy(Error('Self-test request timeout')))
      request.once('error', reject)
      request.end(extraHeaders['Content-Length'] ? 'x' : undefined)
    })
    let response = await get(url, { headers })
    assert.equal(response.status, 200)
    assert.equal((await response.json()).data.status, 'RUNNING')
    assert.equal(response.headers.get('cache-control'), 'no-store')
    mock.complete('success')
    const success = (await (await get(url, { headers })).json()).data
    assert.equal(success.status, 'SUCCEEDED')
    assert.equal(success.jobId, ids.success)
    assert.ok(Number.isSafeInteger(success.attemptCount))
    assert.ok(typeof success.createdAt === 'string' && typeof success.updatedAt === 'string')
    assert.equal(success.resultSha256.length, 64)
    mock.complete('failure')
    response = await get(`http://[::1]:${mock.port}/v1/web/screenshot-jobs/${ids.failure}`, { headers })
    assert.equal((await response.json()).data.status, 'FAILED')
    const localhost = await get(`http://localhost:${mock.port}/v1/web/screenshot-jobs/${ids.success}`, { headers })
    assert.equal(localhost.status, 200)
    assert.equal((await get(url)).status, 401)
    assert.equal((await get(url, { headers: { Authorization: 'ApiKey NOT_A_REAL_KEY' } })).status, 401)
    assert.equal((await get(url, { method: 'POST', headers, body: '{}' })).status, 405)
    assert.equal(await rawGet({ Host: 'untrusted.example' }), 400)
    assert.equal(await rawGet({ 'Content-Length': '1' }), 400)
    for (const suffix of ['/result', '?unexpected=1', '/../other']) assert.equal((await get(url + suffix, { headers })).status, 404)
    for (const route of ['/v1/web/screenshot-jobs', '/api/auth/login', '/s/test', '/'])
      assert.equal((await get(`http://127.0.0.1:${mock.port}${route}`, { headers })).status, 404)
    await assert.rejects(async () => { conflictProbe = await startMock(mock.port) }, { code: 'EADDRINUSE' })
    for (const [name, source] of Object.entries(snippets())) new vm.Script(source, { filename: name })
    assert.ok(!JSON.stringify(mock.requests).includes(fakeKey))
    assert.ok(!JSON.stringify(mock.requests).includes('NOT_A_REAL_KEY'))
    console.log('PASS: RUNNING/SUCCEEDED/FAILED, IPv4/IPv6/localhost, auth, host/body/route/method rejection, bind-conflict refusal, safe request logs and snippet syntax. No Electron or real backend used.')
  } finally { await conflictProbe?.close(); await mock.close() }
}

async function main() {
  const args = process.argv.slice(2)
  if (args.length === 1 && args[0] === '--prepare') return prepare()
  if (args.length === 1 && args[0] === '--self-test') return selfTest()
  if (args.length === 3 && args[0] === '--serve' && args[1] === '--dir') return serve(checkedDir(args[2]))
  console.log('Usage: node scripts/serve-installed-notification-mock.cjs --prepare | --self-test | --serve --dir <prepared .runtime directory>')
  if (args.length) process.exitCode = 1
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
