import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { performance } from 'node:perf_hooks'
import path from 'node:path'

const apiOrigin = new URL(process.env.NEXUS_EXPERIMENT_API_ORIGIN ?? 'http://127.0.0.1:8080')
const managementOrigin = new URL(process.env.NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN ?? 'http://127.0.0.1:8081')
const outputRoot = process.env.NEXUS_EXPERIMENT_OUTPUT_DIR
  ?? path.resolve('target', 'rate-limit-experiment')
const phases = [
  { name: '5rps', rate: 5, durationSeconds: 15 },
  { name: '10rps', rate: 10, durationSeconds: 15 },
  { name: '12rps', rate: 12, durationSeconds: 15 },
  { name: '20rps', rate: 20, durationSeconds: 15 },
]
const outcomes = [
  'allowed_immediate', 'allowed_after_wait', 'application_queue_full',
  'global_queue_full', 'state_capacity_reached', 'timed_out', 'cancelled', 'unavailable',
]
const usageFailureReasons = [
  'queue_rejected', 'transient_write_exhausted', 'duplicate_unconfirmed', 'write_failed',
]
const maxInFlight = 64
const requestTimeoutMs = 3_000
const runId = `obs-rate-${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}`
const runDirectory = path.join(outputRoot, runId)
const agent = new http.Agent({ keepAlive: true, maxSockets: maxInFlight })
const suffix = randomBytes(8).toString('hex')
const email = `obs-rate-${suffix}@example.test`
const password = randomBytes(24).toString('base64url')
let bearer = ''
let applicationId
let apiKeyId
let fullApiKey = ''
let softDeleted = false

await mkdir(runDirectory, { recursive: true })

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

async function jsonCall(origin, requestPath, { method = 'GET', body, authorization, expectOk = true } = {}) {
  const response = await fetch(new URL(requestPath, origin), {
    method,
    headers: {
      ...(body && { 'Content-Type': 'application/json' }),
      ...(authorization && { Authorization: authorization }),
    },
    ...(body && { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  const text = await response.text()
  let parsed
  try { parsed = text ? JSON.parse(text) : null } catch { parsed = null }
  if (expectOk) {
    assert(response.ok, `${method} ${requestPath.split('?')[0]} returned ${response.status}`)
    if (parsed && Object.hasOwn(parsed, 'code')) assert.equal(parsed.code, 'SUCCESS')
  }
  return { status: response.status, body: parsed, text }
}

async function apiCall(requestPath, options = {}) {
  const response = await jsonCall(apiOrigin, requestPath, options)
  return response.body?.data
}

async function metric(name, tags = {}) {
  const url = new URL(`/actuator/metrics/${name}`, managementOrigin)
  for (const [key, value] of Object.entries(tags)) url.searchParams.append('tag', `${key}:${value}`)
  const response = await fetch(url, { signal: AbortSignal.timeout(requestTimeoutMs) })
  if (response.status === 404) return null
  assert.equal(response.status, 200, `Metric ${name} returned ${response.status}`)
  return response.json()
}

function measurements(value) {
  return Object.fromEntries((value?.measurements ?? []).map((item) => [item.statistic, item.value]))
}

function statistic(value, name) {
  return Number(value?.[name] ?? 0)
}

function counterDelta(after, before) {
  return statistic(after, 'COUNT') - statistic(before, 'COUNT')
}

async function metricValue(name, tags = {}) {
  return measurements(await metric(name, tags)).VALUE
}

async function timerSnapshot(name, tags = {}) {
  return measurements(await metric(name, tags))
}

async function admissionSnapshot() {
  return Object.fromEntries(await Promise.all(outcomes.map(async (outcome) => [
    outcome, await timerSnapshot('nexus.ratelimit.admission', { outcome }),
  ])))
}

async function usageFailuresSnapshot() {
  return Object.fromEntries(await Promise.all(usageFailureReasons.map(async (reason) => [
    reason, measurements(await metric('nexus.usage.record.failures', { reason })),
  ])))
}

async function boundarySnapshot() {
  return {
    at: new Date().toISOString(),
    admission: await admissionSnapshot(),
    http200: await timerSnapshot('http.server.requests', {
      method: 'GET', uri: '/v1/utils/uuid', status: '200',
    }),
    http429: await timerSnapshot('http.server.requests', {
      method: 'GET', uri: 'UNKNOWN', status: '429',
    }),
    hikariAcquire: await timerSnapshot('hikaricp.connections.acquire'),
    usageFailures: await usageFailuresSnapshot(),
    processCpu: await metricValue('process.cpu.usage'),
    systemCpu: await metricValue('system.cpu.usage'),
    heapUsed: await metricValue('jvm.memory.used', { area: 'heap' }),
    heapMax: await metricValue('jvm.memory.max', { area: 'heap' }),
    liveThreads: await metricValue('jvm.threads.live'),
    gcPause: await timerSnapshot('jvm.gc.pause'),
  }
}

async function prometheusSnapshot(phase, point) {
  const response = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(response.status, 200, 'Prometheus endpoint must return 200')
  const text = await response.text()
  await writeFile(path.join(runDirectory, `${phase}-${point}-prometheus.txt`), text, 'utf8')
  return { point, at: new Date().toISOString(), bytes: Buffer.byteLength(text) }
}

async function quota() {
  return apiCall(`/api/application/${applicationId}/quota`, {
    authorization: `Bearer ${bearer}`,
  })
}

async function usageCount() {
  const query = new URLSearchParams({
    applicationId: String(applicationId), apiKeyId: String(apiKeyId), timeRange: 'TODAY',
  })
  const data = await apiCall(`/api/usage?${query}`, { authorization: `Bearer ${bearer}` })
  return data.allTimeCount
}

async function waitForUsage(expected) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (await usageCount() === expected) return
    await sleep(250)
  }
  throw new Error(`Usage did not converge to ${expected} within 20 seconds`)
}

async function waitForColdState() {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const [waiters, states] = await Promise.all([
      metricValue('nexus.ratelimit.waiters'),
      metricValue('nexus.ratelimit.states'),
    ])
    if (waiters === 0 && states === 0) return
    await sleep(250)
  }
  throw new Error('Rate limiter did not return to waiters=0 and states=0 within 25 seconds')
}

function uuidRequest() {
  return new Promise((resolve) => {
    const started = performance.now()
    const request = http.request({
      protocol: apiOrigin.protocol,
      hostname: apiOrigin.hostname,
      port: apiOrigin.port,
      path: '/v1/utils/uuid',
      method: 'GET',
      agent,
      headers: { Authorization: `ApiKey ${fullApiKey}` },
      timeout: requestTimeoutMs,
    }, (response) => {
      const chunks = []
      response.on('data', (chunk) => chunks.push(chunk))
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let code
        try { code = JSON.parse(text)?.code } catch { code = undefined }
        resolve({ status: response.statusCode, code, durationMs: performance.now() - started })
      })
    })
    request.on('timeout', () => request.destroy(new Error('client_timeout')))
    request.on('error', (error) => resolve({
      status: 0, code: error.message, durationMs: performance.now() - started,
    }))
    request.end()
  })
}

function startObserver(startedAt) {
  const samples = []
  let stopped = false
  const loop = (async () => {
    let nextWaiter = 0
    let nextHikari = 0
    let nextJvm = 0
    while (!stopped) {
      const elapsed = performance.now() - startedAt
      const sample = { elapsedMs: elapsed }
      if (elapsed >= nextWaiter) {
        const [waiters, states] = await Promise.all([
          metricValue('nexus.ratelimit.waiters'), metricValue('nexus.ratelimit.states'),
        ])
        sample.waiters = waiters
        sample.states = states
        nextWaiter += 200
      }
      if (elapsed >= nextHikari) {
        const [active, idle, pending, maximum] = await Promise.all([
          metricValue('hikaricp.connections.active'), metricValue('hikaricp.connections.idle'),
          metricValue('hikaricp.connections.pending'), metricValue('hikaricp.connections.max'),
        ])
        Object.assign(sample, { hikariActive: active, hikariIdle: idle, hikariPending: pending, hikariMax: maximum })
        nextHikari += 500
      }
      if (elapsed >= nextJvm) {
        const [processCpu, heapUsed, heapMax, liveThreads] = await Promise.all([
          metricValue('process.cpu.usage'), metricValue('jvm.memory.used', { area: 'heap' }),
          metricValue('jvm.memory.max', { area: 'heap' }), metricValue('jvm.threads.live'),
        ])
        Object.assign(sample, { processCpu, heapUsed, heapMax, liveThreads })
        nextJvm += 1_000
      }
      if (Object.keys(sample).length > 1) samples.push(sample)
      await sleep(40)
    }
  })()
  return {
    samples,
    async stop() { stopped = true; await loop },
  }
}

async function runPhase(phase) {
  await waitForColdState()
  const usageBefore = await usageCount()
  const before = await boundarySnapshot()
  const prometheus = [await prometheusSnapshot(phase.name, 'before')]
  const intervalMs = 1_000 / phase.rate
  const planned = phase.rate * phase.durationSeconds
  const startedAt = performance.now()
  const observer = startObserver(startedAt)
  const results = []
  let inFlight = 0
  let maxInFlightObserved = 0
  let abortReason

  const midpoint = setTimeout(async () => {
    try { prometheus.push(await prometheusSnapshot(phase.name, 'mid')) }
    catch (error) { abortReason = `prometheus_midpoint:${error.message}` }
  }, phase.durationSeconds * 500)

  await new Promise((resolve) => {
    let completed = 0
    for (let index = 0; index < planned; index += 1) {
      const due = index * intervalMs
      setTimeout(async () => {
        const latenessMs = performance.now() - startedAt - due
        if (!abortReason && latenessMs > intervalMs) abortReason = `scheduler_late:${latenessMs.toFixed(1)}ms`
        if (!abortReason && inFlight >= maxInFlight) abortReason = `in_flight_limit:${inFlight}`
        if (abortReason) {
          results.push({ status: -1, code: 'NOT_SENT', durationMs: 0, latenessMs })
        } else {
          inFlight += 1
          maxInFlightObserved = Math.max(maxInFlightObserved, inFlight)
          const result = await uuidRequest()
          inFlight -= 1
          results.push({ ...result, latenessMs })
        }
        completed += 1
        if (completed === planned) resolve()
      }, due)
    }
  })

  clearTimeout(midpoint)
  await observer.stop()
  prometheus.push(await prometheusSnapshot(phase.name, 'after'))
  const completed200 = results.filter((item) => item.status === 200).length
  const completed429 = results.filter((item) => item.status === 429).length
  const unexpected = results.filter((item) => ![200, 429].includes(item.status))
  await waitForUsage(usageBefore + completed200)
  const quotaAfter = await quota()
  await waitForColdState()
  const after = await boundarySnapshot()

  const durations = results.filter((item) => item.status > 0).map((item) => item.durationMs).sort((a, b) => a - b)
  const percentile = (fraction) => durations.length
    ? durations[Math.min(durations.length - 1, Math.floor((durations.length - 1) * fraction))]
    : null
  return {
    phase,
    planned,
    abortReason,
    counts: Object.fromEntries([...new Set(results.map((item) => `${item.status}:${item.code ?? ''}`))]
      .map((key) => [key, results.filter((item) => `${item.status}:${item.code ?? ''}` === key).length])),
    completed200,
    completed429,
    unexpected,
    latencyMs: { p50: percentile(0.50), p95: percentile(0.95), p99: percentile(0.99), max: durations.at(-1) ?? null },
    maxSchedulingLatenessMs: Math.max(...results.map((item) => item.latenessMs)),
    maxInFlightObserved,
    observerSamples: observer.samples,
    prometheus,
    before,
    after,
    quotaAfter,
  }
}

async function setup() {
  const health = await jsonCall(managementOrigin, '/actuator/health')
  assert.equal(health.body?.status, 'UP')
  assert.equal(await metricValue('nexus.ratelimit.waiters'), 0)
  assert.equal(await metricValue('nexus.ratelimit.states'), 0)

  await apiCall('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Rate experiment ${suffix}`, password },
  })
  const login = await apiCall('/api/auth/login', { method: 'POST', body: { email, password } })
  bearer = login.accessToken
  const application = await apiCall('/api/application/create', {
    method: 'POST', body: { name: `obs-rate-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  applicationId = application.id
  const key = await apiCall('/api/apiKey/create', {
    method: 'POST', body: { applicationId, name: `obs-rate-key-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  apiKeyId = key.id
  fullApiKey = key.apiKey
  const initialQuota = await quota()
  assert.equal(initialQuota.quotaLimit, 1_000)
  assert.equal(initialQuota.usedCount, 0)

  const first = await uuidRequest()
  assert.equal(first.status, 200)
  await waitForUsage(1)
  const afterPreflightQuota = await quota()
  assert.equal(afterPreflightQuota.usedCount, 1)
  await waitForColdState()
  return { initialQuota, afterPreflightQuota }
}

async function softDelete() {
  if (!applicationId || !bearer || softDeleted) return
  await apiCall(`/api/application/${applicationId}`, {
    method: 'DELETE', authorization: `Bearer ${bearer}`,
  })
  softDeleted = true
}

const result = { runId, startedAt: new Date().toISOString(), phases: [] }
let failed
try {
  result.preflight = await setup()
  let cumulativeSuccess = 1
  for (const phase of phases) {
    const phaseResult = await runPhase(phase)
    cumulativeSuccess += phaseResult.completed200
    assert.equal(phaseResult.quotaAfter.usedCount, cumulativeSuccess,
      `Quota mismatch after ${phase.name}`)
    assert.equal(await usageCount(), cumulativeSuccess,
      `Usage mismatch after ${phase.name}`)
    result.phases.push(phaseResult)
    console.log(`${phase.name}: 200=${phaseResult.completed200} 429=${phaseResult.completed429} p95=${phaseResult.latencyMs.p95?.toFixed(1)}ms`)
    if (phaseResult.abortReason || phaseResult.unexpected.length) {
      throw new Error(`Client invalidated ${phase.name}: ${phaseResult.abortReason ?? 'unexpected response'}`)
    }
    const maxWaiters = Math.max(0, ...phaseResult.observerSamples.map((item) => item.waiters ?? 0))
    const maxStates = Math.max(0, ...phaseResult.observerSamples.map((item) => item.states ?? 0))
    const maxPending = Math.max(0, ...phaseResult.observerSamples.map((item) => item.hikariPending ?? 0))
    const failureDelta = usageFailureReasons.reduce((sum, reason) => sum + counterDelta(
      phaseResult.after.usageFailures[reason], phaseResult.before.usageFailures[reason]), 0)
    if (maxWaiters > 10 || maxStates > 1) {
      throw new Error(`Limiter invariant failed in ${phase.name}: waiters=${maxWaiters}, states=${maxStates}`)
    }
    if (maxPending > 0) {
      throw new Error(`Hikari became a competing bottleneck in ${phase.name}: pending=${maxPending}`)
    }
    if (failureDelta > 0) {
      throw new Error(`Usage recording failed in ${phase.name}: delta=${failureDelta}`)
    }
  }
  result.completedAt = new Date().toISOString()
} catch (error) {
  failed = error
  result.failedAt = new Date().toISOString()
  result.failure = { name: error.name, message: error.message }
} finally {
  try { await softDelete() } catch (error) {
    result.softDeleteFailure = error.message
    failed ??= error
  }
  agent.destroy()
  result.resources = { email, applicationId, apiKeyId, softDeleted }
  await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
  console.log(`RESULT_FILE=${path.join(runDirectory, 'result.json')}`)
  console.log(`CLEANUP applicationId=${applicationId ?? 0} apiKeyId=${apiKeyId ?? 0} email=${email}`)
}
if (failed) throw failed
