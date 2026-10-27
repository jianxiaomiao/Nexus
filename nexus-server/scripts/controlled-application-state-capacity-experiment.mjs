import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { performance } from 'node:perf_hooks'
import path from 'node:path'
import { promisify } from 'node:util'
import { Worker, isMainThread, parentPort, threadId, workerData } from 'node:worker_threads'

const execFileAsync = promisify(execFile)
const apiOrigin = new URL(process.env.NEXUS_EXPERIMENT_API_ORIGIN ?? 'http://127.0.0.1:18180')
const managementOrigin = new URL(process.env.NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN ?? 'http://127.0.0.1:18181')
const outputRoot = process.env.NEXUS_EXPERIMENT_OUTPUT_DIR
  ?? path.resolve('target', 'application-state-capacity-experiment')

const applicationCount = 1_001
const controlCount = 999
const stateCapacity = 1_000
const rounds = 3
const senderWorkerCount = 10
const aggregateRate = 150
const senderIntervalMs = 1_000 / (aggregateRate / senderWorkerCount)
const maxInFlightPerWorker = 8
const requestTimeoutMs = 3_000
const admissionOutcomes = [
  'allowed_immediate', 'allowed_after_wait', 'application_queue_full',
  'global_queue_full', 'state_capacity_reached', 'timed_out', 'cancelled', 'unavailable',
]
const usageFailureReasons = [
  'queue_rejected', 'transient_write_exhausted', 'duplicate_unconfirmed', 'write_failed',
]
const runId = isMainThread
  ? `obs-state-capacity-${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}`
  : workerData.runId
const runDirectory = isMainThread ? path.join(outputRoot, runId) : workerData.runDirectory

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

async function sleepUntil(epochMs) {
  const remaining = epochMs - Date.now()
  if (remaining > 0) await sleep(remaining)
}

async function jsonCall(origin, requestPath, {
  method = 'GET', body, authorization, expectOk = true,
} = {}) {
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
  return (await jsonCall(apiOrigin, requestPath, options)).body?.data
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
  return measurements(await metric(name, tags)).VALUE ?? 0
}

async function timerSnapshot(name, tags = {}) {
  return measurements(await metric(name, tags))
}

async function admissionSnapshot() {
  return Object.fromEntries(await Promise.all(admissionOutcomes.map(async (outcome) => [
    outcome, await timerSnapshot('nexus.ratelimit.admission', { outcome }),
  ])))
}

async function usageFailureSnapshot() {
  return Object.fromEntries(await Promise.all(usageFailureReasons.map(async (reason) => [
    reason, measurements(await metric('nexus.usage.record.failures', { reason })),
  ])))
}

async function boundarySnapshot() {
  return {
    at: new Date().toISOString(),
    states: await metricValue('nexus.ratelimit.states'),
    waiters: await metricValue('nexus.ratelimit.waiters'),
    admission: await admissionSnapshot(),
    http200: await timerSnapshot('http.server.requests', {
      method: 'GET', uri: '/v1/utils/uuid', status: '200',
    }),
    http429: await timerSnapshot('http.server.requests', {
      method: 'GET', uri: 'UNKNOWN', status: '429',
    }),
    http503: await timerSnapshot('http.server.requests', {
      method: 'GET', uri: 'UNKNOWN', status: '503',
    }),
    hikariAcquire: await timerSnapshot('hikaricp.connections.acquire'),
    hikariTimeout: measurements(await metric('hikaricp.connections.timeout')),
    usageFailures: await usageFailureSnapshot(),
    gcPause: await timerSnapshot('jvm.gc.pause'),
  }
}

async function prometheusSnapshot(roundName, point) {
  const response = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(response.status, 200, 'Prometheus endpoint must return 200')
  const text = await response.text()
  const filename = `${roundName}-${point}-prometheus.txt`
  await writeFile(path.join(runDirectory, filename), text, 'utf8')
  return {
    meta: { point, at: new Date().toISOString(), filename, bytes: Buffer.byteLength(text) },
    text,
  }
}

function prometheusValue(text, metricName, labels = {}) {
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith(metricName)) continue
    if (!Object.entries(labels).every(([key, value]) => line.includes(`${key}="${value}"`))) continue
    const value = Number(line.slice(line.lastIndexOf(' ') + 1))
    if (Number.isFinite(value)) return value
  }
  return 0
}

function assertOutletAgreement(before, after, beforePrometheus, afterPrometheus, label) {
  for (const outcome of admissionOutcomes) {
    const actuator = counterDelta(after.admission[outcome], before.admission[outcome])
    const prometheus = prometheusValue(afterPrometheus, 'nexus_ratelimit_admission_seconds_count', { outcome })
      - prometheusValue(beforePrometheus, 'nexus_ratelimit_admission_seconds_count', { outcome })
    assert.equal(prometheus, actuator, `${label}: admission outlet mismatch for ${outcome}`)
  }
  for (const [status, uri] of [['200', '/v1/utils/uuid'], ['429', 'UNKNOWN'], ['503', 'UNKNOWN']]) {
    const actuator = counterDelta(after[`http${status}`], before[`http${status}`])
    const prometheus = prometheusValue(afterPrometheus, 'http_server_requests_seconds_count', { status, uri })
      - prometheusValue(beforePrometheus, 'http_server_requests_seconds_count', { status, uri })
    assert.equal(prometheus, actuator, `${label}: HTTP ${status} outlet mismatch`)
  }
  for (const reason of usageFailureReasons) {
    const actuator = counterDelta(after.usageFailures[reason], before.usageFailures[reason])
    const prometheus = prometheusValue(afterPrometheus, 'nexus_usage_record_failures_total', { reason })
      - prometheusValue(beforePrometheus, 'nexus_usage_record_failures_total', { reason })
    assert.equal(prometheus, actuator, `${label}: Usage failure outlet mismatch for ${reason}`)
  }
}

function uuidRequest(fullApiKey, agent) {
  return new Promise((resolve, reject) => {
    const started = performance.now()
    const request = http.request({
      protocol: apiOrigin.protocol,
      hostname: apiOrigin.hostname,
      port: apiOrigin.port,
      path: '/v1/utils/uuid',
      method: 'GET',
      agent,
      headers: { Authorization: `ApiKey ${fullApiKey}` },
    }, (response) => {
      const chunks = []
      response.on('data', (chunk) => chunks.push(chunk))
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let parsed
        try { parsed = text ? JSON.parse(text) : null } catch { parsed = null }
        resolve({
          status: response.statusCode,
          code: parsed?.code ?? null,
          latencyMs: performance.now() - started,
          completedAtEpochMs: Date.now(),
        })
      })
    })
    request.setTimeout(requestTimeoutMs, () => request.destroy(new Error('request_timeout')))
    request.on('error', reject)
    request.end()
  })
}

function percentile(values, fraction) {
  if (values.length === 0) return 0
  const sorted = [...values].sort((left, right) => left - right)
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)]
}

async function runSenderWorker(data) {
  const agent = new http.Agent({ keepAlive: true, maxSockets: maxInFlightPerWorker })
  let inFlight = 0
  let maxInFlight = 0
  let maxLatenessMs = 0
  let scheduleFailure = null
  const pending = []
  try {
    for (const item of data.items) {
      const targetEpochMs = data.startAtEpochMs + item.offsetMs
      await sleepUntil(targetEpochMs)
      const latenessMs = Math.max(0, Date.now() - targetEpochMs)
      maxLatenessMs = Math.max(maxLatenessMs, latenessMs)
      if (latenessMs > senderIntervalMs) {
        scheduleFailure = `lateness:${latenessMs.toFixed(3)}`
        break
      }
      if (inFlight >= maxInFlightPerWorker) {
        scheduleFailure = `in_flight:${inFlight}`
        break
      }
      inFlight += 1
      maxInFlight = Math.max(maxInFlight, inFlight)
      const task = uuidRequest(item.fullApiKey, agent)
        .then((response) => ({ ordinal: item.ordinal, ...response }))
        .finally(() => { inFlight -= 1 })
      pending.push(task)
    }
    const settled = await Promise.allSettled(pending)
    const responses = settled.filter((item) => item.status === 'fulfilled').map((item) => item.value)
    const failures = settled.filter((item) => item.status === 'rejected').map((item) => item.reason.message)
    return {
      role: data.role,
      threadId,
      planned: data.items.length,
      sent: pending.length,
      responses,
      failures,
      scheduleFailure,
      maxInFlight,
      maxLatenessMs,
    }
  } finally {
    agent.destroy()
  }
}

async function osSample() {
  const command = String.raw`
    $ErrorActionPreference='Stop'
    Add-Type -AssemblyName Microsoft.VisualBasic
    $computer=[Microsoft.VisualBasic.Devices.ComputerInfo]::new()
    $java=Get-Process -Id ([int]$env:NEXUS_EXPERIMENT_SERVER_PID) -ErrorAction Stop
    $javaCpu=if ($null -eq $java.CPU) { 0 } else { $java.CPU }
    [pscustomobject]@{
      totalPhysicalBytes=[uint64]$computer.TotalPhysicalMemory
      availablePhysicalBytes=[uint64]$computer.AvailablePhysicalMemory
      javaWorkingSetBytes=[uint64]$java.WorkingSet64
      javaPrivateBytes=[uint64]$java.PrivateMemorySize64
      javaCpuSeconds=[double]$javaCpu
      javaThreads=$java.Threads.Count
      javaHandles=$java.HandleCount
    } | ConvertTo-Json -Compress
  `
  const { stdout } = await execFileAsync('powershell.exe', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', command,
  ], { timeout: 5_000, windowsHide: true })
  return JSON.parse(stdout.trim())
}

async function runMetricsWorker() {
  let stopped = false
  parentPort.on('message', (message) => {
    if (message?.type === 'stop') stopped = true
  })
  const samples = []
  let tick = 0
  let consecutiveHighCpu = 0
  let consecutiveHighTomcat = 0
  let safetyFailure = null
  while (!stopped) {
    const started = performance.now()
    const sample = {
      atEpochMs: Date.now(),
      states: await metricValue('nexus.ratelimit.states'),
      waiters: await metricValue('nexus.ratelimit.waiters'),
    }
    if (tick % 5 === 0) {
      Object.assign(sample, {
        hikariActive: await metricValue('hikaricp.connections.active'),
        hikariIdle: await metricValue('hikaricp.connections.idle'),
        hikariPending: await metricValue('hikaricp.connections.pending'),
        hikariMax: await metricValue('hikaricp.connections.max'),
        tomcatBusy: await metricValue('tomcat.threads.busy'),
        tomcatCurrent: await metricValue('tomcat.threads.current'),
        tomcatMax: await metricValue('tomcat.threads.config.max'),
        tomcatConnections: await metricValue('tomcat.connections.current'),
      })
      consecutiveHighTomcat = sample.tomcatMax > 0
        && sample.tomcatBusy >= sample.tomcatMax * 0.9
        ? consecutiveHighTomcat + 1 : 0
    }
    if (tick % 10 === 0) {
      const operatingSystem = await osSample()
      Object.assign(sample, {
        processCpu: await metricValue('process.cpu.usage'),
        systemCpu: await metricValue('system.cpu.usage'),
        heapUsed: await metricValue('jvm.memory.used', { area: 'heap' }),
        heapMax: await metricValue('jvm.memory.max', { area: 'heap' }),
        liveThreads: await metricValue('jvm.threads.live'),
        ...operatingSystem,
      })
      const minimumAvailable = Math.max(1024 ** 3, sample.totalPhysicalBytes * 0.15)
      if (sample.availablePhysicalBytes < minimumAvailable) {
        safetyFailure = `physical_memory_headroom:${sample.availablePhysicalBytes}`
      }
      consecutiveHighCpu = sample.systemCpu >= 0.95 ? consecutiveHighCpu + 1 : 0
    }
    if (sample.states > stateCapacity) safetyFailure = `state_capacity:${sample.states}`
    if (consecutiveHighCpu >= 5) safetyFailure = 'system_cpu_above_95_percent_for_5_samples'
    if (consecutiveHighTomcat >= 10) safetyFailure = 'tomcat_above_90_percent_for_5_seconds'
    samples.push(sample)
    if (safetyFailure) {
      parentPort.postMessage({ type: 'safety', reason: safetyFailure })
      break
    }
    tick += 1
    await sleep(Math.max(0, 100 - (performance.now() - started)))
  }
  return { role: 'metrics', threadId, samples, safetyFailure }
}

async function runClockProbeWorker(data) {
  const intervalMs = data.intervalMs
  let maxLatenessMs = 0
  for (let tick = 0; tick < data.ticks; tick++) {
    const target = data.startAtEpochMs + tick * intervalMs
    await sleepUntil(target)
    maxLatenessMs = Math.max(maxLatenessMs, Math.max(0, Date.now() - target))
  }
  return { role: data.role, threadId, intervalMs, maxLatenessMs, ticks: data.ticks }
}

async function runListenerLifecycleProbeWorker() {
  let stopped = false
  parentPort.on('message', (message) => {
    if (message?.type === 'stop') stopped = true
  })
  while (!stopped) await sleep(10)
  return { role: 'listener-lifecycle-probe', threadId }
}

async function runWorkerEntry() {
  try {
    let result
    if (workerData.kind === 'sender') result = await runSenderWorker(workerData)
    else if (workerData.kind === 'metrics') result = await runMetricsWorker()
    else if (workerData.kind === 'clock-probe') result = await runClockProbeWorker(workerData)
    else if (workerData.kind === 'listener-lifecycle-probe') {
      result = await runListenerLifecycleProbeWorker()
    }
    else throw new Error(`Unknown worker kind: ${workerData.kind}`)
    parentPort.postMessage({ type: 'result', result })
  } catch (error) {
    parentPort.postMessage({ type: 'failure', error: { name: error.name, message: error.message } })
  } finally {
    // A metrics worker owns a message listener. Closing the port after its final
    // result is what lets the worker, and therefore the coordinator, exit.
    parentPort.close()
  }
}

function spawnWorker(data, onSafety = () => {}) {
  const worker = new Worker(new URL(import.meta.url), {
    workerData: { ...data, runId, runDirectory },
  })
  const result = new Promise((resolve, reject) => {
    worker.on('message', (message) => {
      if (message?.type === 'safety') onSafety(message.reason)
      else if (message?.type === 'result') resolve(message.result)
      else if (message?.type === 'failure') reject(new Error(message.error.message))
    })
    worker.on('error', reject)
    worker.on('exit', (code) => {
      if (code !== 0) reject(new Error(`Worker exited with code ${code}`))
    })
  })
  return { worker, result }
}

async function workerIsolationPreflight() {
  const startAtEpochMs = Date.now() + 1_000
  const roles = [
    ...Array.from({ length: senderWorkerCount }, (_, index) => ({
      role: `sender-${index + 1}`, intervalMs: senderIntervalMs,
    })),
    { role: 'metrics', intervalMs: 100 },
  ]
  const handles = roles.map((role) => spawnWorker({
    kind: 'clock-probe', ...role, startAtEpochMs, ticks: 20,
  }))
  const results = await Promise.all(handles.map((handle) => handle.result))
  assert.equal(new Set(results.map((result) => result.threadId)).size, roles.length)
  for (const result of results) {
    assert(result.maxLatenessMs <= result.intervalMs,
      `${result.role} exceeded one scheduling interval`)
  }
  const lifecycleHandle = spawnWorker({ kind: 'listener-lifecycle-probe' })
  const lifecycleExit = new Promise((resolve, reject) => {
    lifecycleHandle.worker.once('exit', (code) => {
      if (code === 0) resolve(code)
      else reject(new Error(`Lifecycle probe exited with code ${code}`))
    })
  })
  lifecycleHandle.worker.postMessage({ type: 'stop' })
  const lifecycleResult = await lifecycleHandle.result
  await Promise.race([
    lifecycleExit,
    sleep(2_000).then(() => { throw new Error('Listener worker did not exit after result') }),
  ])
  return {
    workerCount: results.length,
    maxLatenessMs: Math.max(...results.map((result) => result.maxLatenessMs)),
    workers: results,
    listenerLifecycle: { threadId: lifecycleResult.threadId, exited: true },
  }
}

async function servicePreflight() {
  const health = await jsonCall(managementOrigin, '/actuator/health')
  assert.equal(health.body?.status, 'UP')
  assert.equal(await metricValue('nexus.ratelimit.states'), 0)
  assert.equal(await metricValue('nexus.ratelimit.waiters'), 0)
  const unauthenticated = await jsonCall(apiOrigin, '/v1/utils/uuid', { expectOk: false })
  assert.equal(unauthenticated.status, 401)
  const names = (await jsonCall(managementOrigin, '/actuator/metrics')).body?.names ?? []
  const required = [
    'nexus.ratelimit.admission', 'nexus.ratelimit.states', 'nexus.ratelimit.waiters',
    'nexus.usage.record.failures', 'http.server.requests',
    'hikaricp.connections.active', 'hikaricp.connections.idle',
    'hikaricp.connections.pending', 'hikaricp.connections.max',
    'hikaricp.connections.acquire', 'hikaricp.connections.timeout',
    'tomcat.threads.busy', 'tomcat.threads.current', 'tomcat.threads.config.max',
    'tomcat.connections.current', 'process.cpu.usage', 'system.cpu.usage',
    'jvm.memory.used', 'jvm.memory.max', 'jvm.threads.live',
  ]
  assert.deepEqual(required.filter((name) => !names.includes(name)), [])
  assert.equal(await metricValue('hikaricp.connections.max'), 10)
  const prometheus = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  const prometheusText = await prometheus.text()
  assert.equal(prometheus.status, 200)
  assert(prometheusText.includes('nexus_ratelimit_states '))
  assert(prometheusText.includes('tomcat_threads_busy_threads'))
  return { health: 'UP', hikariMax: 10, requiredMetrics: required }
}

async function writeResourceManifest(email, resources) {
  await writeFile(path.join(runDirectory, 'resource-manifest.json'), JSON.stringify({
    email,
    applications: resources.map((resource) => ({
      ordinal: resource.ordinal,
      applicationId: resource.applicationId,
      apiKeyId: resource.apiKeyId,
    })),
  }, null, 2), 'utf8')
}

async function createResources() {
  const suffix = randomBytes(8).toString('hex')
  const email = `obs-state-${suffix}@example.test`
  const password = randomBytes(24).toString('base64url')
  const resources = []
  console.log(`RESOURCE_EMAIL=${email}`)
  await apiCall('/api/auth/register', {
    method: 'POST', body: { email, displayName: `State capacity ${suffix}`, password },
  })
  const login = await apiCall('/api/auth/login', {
    method: 'POST', body: { email, password },
  })
  const bearer = login.accessToken
  await writeResourceManifest(email, resources)
  for (let index = 0; index < applicationCount; index++) {
    const ordinal = index + 1
    const application = await apiCall('/api/application/create', {
      method: 'POST',
      body: { name: `obs-state-app-${suffix}-${ordinal}` },
      authorization: `Bearer ${bearer}`,
    })
    const key = await apiCall('/api/apiKey/create', {
      method: 'POST',
      body: { applicationId: application.id, name: `obs-state-key-${suffix}-${ordinal}` },
      authorization: `Bearer ${bearer}`,
    })
    resources.push({
      ordinal, applicationId: application.id, apiKeyId: key.id, fullApiKey: key.apiKey,
    })
    if (ordinal % 25 === 0 || ordinal === applicationCount) {
      await writeResourceManifest(email, resources)
    }
    if (ordinal % 100 === 0 || ordinal === applicationCount) {
      console.log(`RESOURCE_PROGRESS=${ordinal}/${applicationCount}`)
    }
  }
  return { email, bearer, resources }
}

async function waitForStateZero(deadlineEpochMs, safetyState) {
  let firstBelowCapacityAtEpochMs = null
  while (Date.now() < deadlineEpochMs) {
    if (safetyState.failure) throw new Error(`Safety stop: ${safetyState.failure}`)
    const states = await metricValue('nexus.ratelimit.states')
    const waiters = await metricValue('nexus.ratelimit.waiters')
    assert.equal(waiters, 0, 'State-only workload must not create waiters')
    if (states < stateCapacity && firstBelowCapacityAtEpochMs == null) {
      firstBelowCapacityAtEpochMs = Date.now()
    }
    if (states === 0) return { firstBelowCapacityAtEpochMs, zeroAtEpochMs: Date.now() }
    await sleep(100)
  }
  throw new Error('Rate-limit states did not return to zero before the recovery deadline')
}

function maxSample(samples, property) {
  return Math.max(0, ...samples
    .map((sample) => sample[property])
    .filter((value) => Number.isFinite(value)))
}

function countStatuses(responses) {
  const counts = {}
  for (const response of responses) counts[response.status] = (counts[response.status] ?? 0) + 1
  return counts
}

async function runRound(resourceGroup, roundNumber) {
  const name = `round-${roundNumber}`
  assert.equal(await metricValue('nexus.ratelimit.states'), 0)
  assert.equal(await metricValue('nexus.ratelimit.waiters'), 0)
  const before = await boundarySnapshot()
  const beforePrometheus = await prometheusSnapshot(name, 'before')
  const safetyState = { failure: null }
  let senderHandles = []
  const observer = spawnWorker({ kind: 'metrics' }, (reason) => {
    safetyState.failure ??= reason
    for (const handle of senderHandles) handle.worker.terminate()
  })
  const startAtEpochMs = Date.now() + 1_500
  const bulkResources = resourceGroup.resources.slice(0, controlCount)
  senderHandles = Array.from({ length: senderWorkerCount }, (_, workerIndex) => {
    const items = bulkResources
      .map((resource, globalIndex) => ({ resource, globalIndex }))
      .filter((item) => item.globalIndex % senderWorkerCount === workerIndex)
      .map(({ resource, globalIndex }) => ({
        ordinal: resource.ordinal,
        fullApiKey: resource.fullApiKey,
        offsetMs: globalIndex * (1_000 / aggregateRate),
      }))
    return spawnWorker({
      kind: 'sender', role: `sender-${workerIndex + 1}`, items, startAtEpochMs,
    })
  })

  let observerResult
  try {
    const senderResults = await Promise.all(senderHandles.map((handle) => handle.result))
    if (safetyState.failure) throw new Error(`Safety stop: ${safetyState.failure}`)
    const bulkResponses = senderResults.flatMap((result) => result.responses)
    assert.equal(senderResults.reduce((sum, result) => sum + result.planned, 0), controlCount)
    assert.equal(senderResults.reduce((sum, result) => sum + result.sent, 0), controlCount)
    assert.equal(bulkResponses.length, controlCount)
    assert.equal(senderResults.flatMap((result) => result.failures).length, 0)
    assert(senderResults.every((result) => result.scheduleFailure == null))
    assert(senderResults.every((result) => result.maxLatenessMs <= senderIntervalMs))
    assert(senderResults.every((result) => result.maxInFlight <= maxInFlightPerWorker))
    assert.deepEqual(countStatuses(bulkResponses), { 200: controlCount })
    const lastBulkCompletedAt = Math.max(...bulkResponses.map((response) => response.completedAtEpochMs))
    const bulkDurationMs = lastBulkCompletedAt - startAtEpochMs
    assert(bulkDurationMs <= 8_000, `Bulk fill took ${bulkDurationMs}ms and may overlap TTL cleanup`)
    assert(percentile(bulkResponses.map((response) => response.latencyMs), 0.95) < 1_000,
      'Bulk client p95 exceeded the experiment health boundary')

    const control = await boundarySnapshot()
    assert.equal(control.states, controlCount)
    assert.equal(control.waiters, 0)
    const controlPrometheus = await prometheusSnapshot(name, 'control-999')
    assert.equal(prometheusValue(controlPrometheus.text, 'nexus_ratelimit_states'), controlCount)

    const probeAgent = new http.Agent({ keepAlive: true, maxSockets: 1 })
    let thousandth
    let rejected
    let recovery
    try {
      thousandth = await uuidRequest(resourceGroup.resources[999].fullApiKey, probeAgent)
      assert.equal(thousandth.status, 200)
      assert.equal(await metricValue('nexus.ratelimit.states'), stateCapacity)
      rejected = await uuidRequest(resourceGroup.resources[1_000].fullApiKey, probeAgent)
      assert.equal(rejected.status, 503)
      assert.equal(rejected.code, 'RATE_LIMIT_STATE_CAPACITY_REACHED')
      assert.equal(await metricValue('nexus.ratelimit.states'), stateCapacity)
      assert.equal(await metricValue('nexus.ratelimit.waiters'), 0)
      const treatmentPrometheus = await prometheusSnapshot(name, 'treatment-1001')
      assert.equal(prometheusValue(treatmentPrometheus.text, 'nexus_ratelimit_states'), stateCapacity)

      const firstRecovery = await waitForStateZero(
        thousandth.completedAtEpochMs + 16_000, safetyState)
      assert(firstRecovery.firstBelowCapacityAtEpochMs == null
        || firstRecovery.firstBelowCapacityAtEpochMs >= startAtEpochMs + 9_900,
      'A state was reclaimed before the ten-second idle TTL')
      await prometheusSnapshot(name, 'first-zero')

      recovery = await uuidRequest(resourceGroup.resources[1_000].fullApiKey, probeAgent)
      assert.equal(recovery.status, 200)
      assert.equal(await metricValue('nexus.ratelimit.states'), 1)
      const secondRecovery = await waitForStateZero(
        recovery.completedAtEpochMs + 16_000, safetyState)
      await prometheusSnapshot(name, 'recovery-zero')

      observer.worker.postMessage({ type: 'stop' })
      observerResult = await observer.result
      if (observerResult.safetyFailure) throw new Error(`Safety stop: ${observerResult.safetyFailure}`)
      const after = await boundarySnapshot()
      const afterPrometheus = await prometheusSnapshot(name, 'after')
      assertOutletAgreement(before, after, beforePrometheus.text, afterPrometheus.text, name)

      const admission = Object.fromEntries(admissionOutcomes.map((outcome) => [
        outcome, counterDelta(after.admission[outcome], before.admission[outcome]),
      ]))
      assert.equal(admission.allowed_immediate, applicationCount)
      assert.equal(admission.state_capacity_reached, 1)
      for (const outcome of admissionOutcomes.filter(
        (outcome) => !['allowed_immediate', 'state_capacity_reached'].includes(outcome))) {
        assert.equal(admission[outcome], 0, `${name}: unexpected admission outcome ${outcome}`)
      }
      assert.equal(counterDelta(after.http200, before.http200), applicationCount)
      assert.equal(counterDelta(after.http429, before.http429), 0)
      assert.equal(counterDelta(after.http503, before.http503), 1)
      assert.equal(counterDelta(after.hikariTimeout, before.hikariTimeout), 0)
      for (const reason of usageFailureReasons) {
        assert.equal(counterDelta(after.usageFailures[reason], before.usageFailures[reason]), 0)
      }
      assert.equal(maxSample(observerResult.samples, 'states'), stateCapacity)
      assert.equal(maxSample(observerResult.samples, 'waiters'), 0)
      assert.equal(maxSample(observerResult.samples, 'hikariPending'), 0)
      const tomcatMax = maxSample(observerResult.samples, 'tomcatMax')
      assert(maxSample(observerResult.samples, 'tomcatBusy') < tomcatMax)

      return {
        name,
        startAtEpochMs,
        bulkDurationMs,
        bulk: {
          responses: bulkResponses.length,
          statuses: countStatuses(bulkResponses),
          p50Ms: percentile(bulkResponses.map((response) => response.latencyMs), 0.50),
          p95Ms: percentile(bulkResponses.map((response) => response.latencyMs), 0.95),
          p99Ms: percentile(bulkResponses.map((response) => response.latencyMs), 0.99),
          maxLatenessMs: Math.max(...senderResults.map((result) => result.maxLatenessMs)),
          maxInFlightUpperBound: senderResults.reduce(
            (sum, result) => sum + result.maxInFlight, 0),
          workers: senderResults.map((result) => ({
            role: result.role,
            threadId: result.threadId,
            planned: result.planned,
            sent: result.sent,
            maxInFlight: result.maxInFlight,
            maxLatenessMs: result.maxLatenessMs,
          })),
        },
        probes: { thousandth, rejected, recovery },
        cleanup: { first: firstRecovery, second: secondRecovery },
        admission,
        metrics: {
          maxStates: maxSample(observerResult.samples, 'states'),
          maxWaiters: maxSample(observerResult.samples, 'waiters'),
          maxHikariActive: maxSample(observerResult.samples, 'hikariActive'),
          maxHikariPending: maxSample(observerResult.samples, 'hikariPending'),
          maxTomcatBusy: maxSample(observerResult.samples, 'tomcatBusy'),
          maxTomcatConnections: maxSample(observerResult.samples, 'tomcatConnections'),
          maxProcessCpu: maxSample(observerResult.samples, 'processCpu'),
          maxSystemCpu: maxSample(observerResult.samples, 'systemCpu'),
          maxHeapUsed: maxSample(observerResult.samples, 'heapUsed'),
          maxLiveThreads: maxSample(observerResult.samples, 'liveThreads'),
          minAvailablePhysicalBytes: Math.min(...observerResult.samples
            .map((sample) => sample.availablePhysicalBytes)
            .filter((value) => Number.isFinite(value))),
          maxJavaWorkingSetBytes: maxSample(observerResult.samples, 'javaWorkingSetBytes'),
          maxJavaPrivateBytes: maxSample(observerResult.samples, 'javaPrivateBytes'),
        },
        observerSamples: observerResult.samples,
        prometheus: [beforePrometheus.meta, controlPrometheus.meta,
          treatmentPrometheus.meta, afterPrometheus.meta],
      }
    } finally {
      probeAgent.destroy()
    }
  } finally {
    if (!observerResult) {
      observer.worker.postMessage({ type: 'stop' })
      try { observerResult = await observer.result } catch { /* preserve primary failure */ }
    }
    for (const handle of senderHandles) {
      if (handle.worker.threadId !== -1) await handle.worker.terminate()
    }
  }
}

async function runCoordinator() {
  if (process.argv.includes('--worker-preflight')) {
    console.log(JSON.stringify(await workerIsolationPreflight(), null, 2))
    return
  }
  assert.equal(process.env.NEXUS_STATE_LOAD_APPROVAL, 'RUN_APPROVED_STATE_LOAD',
    'Real load requires the approved wrapper')
  await mkdir(runDirectory, { recursive: true })
  const result = {
    runId,
    startedAt: new Date().toISOString(),
    configuration: {
      applicationCount, controlCount, stateCapacity, rounds,
      senderWorkerCount, aggregateRate, senderIntervalMs,
      maxInFlightPerWorker, totalInFlightSafetyCeiling:
        senderWorkerCount * maxInFlightPerWorker,
      requestTimeoutMs,
    },
    completedRounds: [],
  }
  let failure
  try {
    result.workerPreflight = await workerIsolationPreflight()
    result.servicePreflight = await servicePreflight()
    const resourceGroup = await createResources()
    result.resource = { email: resourceGroup.email, applicationCount: resourceGroup.resources.length }
    assert.equal(await metricValue('nexus.ratelimit.states'), 0)
    assert.equal(await metricValue('nexus.ratelimit.waiters'), 0)
    for (let roundNumber = 1; roundNumber <= rounds; roundNumber++) {
      const round = await runRound(resourceGroup, roundNumber)
      result.completedRounds.push(round)
      await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
      console.log(`${round.name}: 200/503=${applicationCount}/1 states=${round.metrics.maxStates} `
        + `hikari=${round.metrics.maxHikariActive}/10 pending=${round.metrics.maxHikariPending} `
        + `tomcatBusy=${round.metrics.maxTomcatBusy} p95=${round.bulk.p95Ms.toFixed(2)}ms`)
    }
    result.completedAt = new Date().toISOString()
  } catch (error) {
    failure = error
    result.failedAt = new Date().toISOString()
    result.failure = { name: error.name, message: error.message }
  } finally {
    await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
    console.log(`RESULT_FILE=${path.join(runDirectory, 'result.json')}`)
  }
  if (failure) throw failure
}

if (isMainThread) await runCoordinator()
else await runWorkerEntry()
