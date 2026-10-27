import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { performance } from 'node:perf_hooks'
import path from 'node:path'
import { Worker, isMainThread, parentPort, threadId, workerData } from 'node:worker_threads'

const apiOrigin = new URL(process.env.NEXUS_EXPERIMENT_API_ORIGIN ?? 'http://127.0.0.1:8080')
const managementOrigin = new URL(process.env.NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN ?? 'http://127.0.0.1:8081')
const outputRoot = process.env.NEXUS_EXPERIMENT_OUTPUT_DIR
  ?? path.resolve('target', 'global-waiter-capacity-experiment')

const aRate = 20
const bRate = 10
const warmupSeconds = 2
const measurementSeconds = 20
const maxInFlightPerApplication = 64
const requestTimeoutMs = 3_000
const globalWaiterCapacity = 100
const runId = isMainThread
  ? `obs-global-waiters-${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}`
  : workerData.runId
const runDirectory = isMainThread
  ? path.join(outputRoot, runId)
  : workerData.runDirectory

const scenarios = [
  { name: 'control-10-apps', bCount: 9, expectGlobalQueueFull: false, repetition: 1 },
  { name: 'treatment-11-apps-r1', bCount: 10, expectGlobalQueueFull: true, repetition: 1 },
  { name: 'treatment-11-apps-r2', bCount: 10, expectGlobalQueueFull: true, repetition: 2 },
  { name: 'treatment-11-apps-r3', bCount: 10, expectGlobalQueueFull: true, repetition: 3 },
]

const admissionOutcomes = [
  'allowed_immediate', 'allowed_after_wait', 'application_queue_full',
  'global_queue_full', 'state_capacity_reached', 'timed_out', 'cancelled', 'unavailable',
]
const usageFailureReasons = [
  'queue_rejected', 'transient_write_exhausted', 'duplicate_unconfirmed', 'write_failed',
]
const forbiddenAdmissionOutcomes = ['state_capacity_reached', 'cancelled', 'unavailable']

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

async function sleepUntil(target) {
  const remaining = target - Date.now()
  if (remaining > 0) await sleep(remaining)
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
  return measurements(await metric(name, tags)).VALUE
}

async function timerSnapshot(name, tags = {}) {
  return measurements(await metric(name, tags))
}

async function admissionSnapshot() {
  return Object.fromEntries(await Promise.all(admissionOutcomes.map(async (outcome) => [
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
    http503: await timerSnapshot('http.server.requests', {
      method: 'GET', uri: 'UNKNOWN', status: '503',
    }),
    hikariAcquire: await timerSnapshot('hikaricp.connections.acquire'),
    hikariTimeout: measurements(await metric('hikaricp.connections.timeout')),
    usageFailures: await usageFailuresSnapshot(),
    processCpu: await metricValue('process.cpu.usage'),
    systemCpu: await metricValue('system.cpu.usage'),
    heapUsed: await metricValue('jvm.memory.used', { area: 'heap' }),
    heapMax: await metricValue('jvm.memory.max', { area: 'heap' }),
    liveThreads: await metricValue('jvm.threads.live'),
    gcPause: await timerSnapshot('jvm.gc.pause'),
    tomcatBusy: await metricValue('tomcat.threads.busy'),
    tomcatCurrent: await metricValue('tomcat.threads.current'),
    tomcatMax: await metricValue('tomcat.threads.config.max'),
    tomcatConnections: await metricValue('tomcat.connections.current'),
    tomcatConnectionsMax: await metricValue('tomcat.connections.config.max'),
  }
}

async function prometheusSnapshot(scenarioName, point) {
  const response = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(response.status, 200, 'Prometheus endpoint must return 200')
  const text = await response.text()
  const filename = `${scenarioName}-${point}-prometheus.txt`
  await writeFile(path.join(runDirectory, filename), text, 'utf8')
  return { meta: { point, at: new Date().toISOString(), filename, bytes: Buffer.byteLength(text) }, text }
}

function prometheusAdmissionCount(text, outcome) {
  const match = text.match(new RegExp(
    `^nexus_ratelimit_admission_seconds_count\\{outcome="${outcome}"\\}\\s+(.+)$`, 'm'))
  return match ? Number(match[1]) : 0
}

function prometheusHttpCount(text, status, uri) {
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith('http_server_requests_seconds_count{')) continue
    if (line.includes(`status="${status}"`) && line.includes(`uri="${uri}"`)) {
      return Number(line.slice(line.lastIndexOf(' ') + 1))
    }
  }
  return 0
}

function prometheusUsageFailureCount(text, reason) {
  const match = text.match(new RegExp(
    `^nexus_usage_record_failures_total\\{reason="${reason}"\\}\\s+(.+)$`, 'm'))
  return match ? Number(match[1]) : 0
}

function assertOutletAgreement(before, after, beforePrometheus, afterPrometheus, label) {
  for (const outcome of admissionOutcomes) {
    const actuatorDelta = counterDelta(after.admission[outcome], before.admission[outcome])
    const prometheusDelta = prometheusAdmissionCount(afterPrometheus, outcome)
      - prometheusAdmissionCount(beforePrometheus, outcome)
    assert.equal(prometheusDelta, actuatorDelta,
      `${label}: admission outlet mismatch for ${outcome}`)
  }
  for (const status of ['200', '429', '503']) {
    const property = `http${status}`
    const uri = status === '200' ? '/v1/utils/uuid' : 'UNKNOWN'
    const actuatorDelta = counterDelta(after[property], before[property])
    const prometheusDelta = prometheusHttpCount(afterPrometheus, status, uri)
      - prometheusHttpCount(beforePrometheus, status, uri)
    assert.equal(prometheusDelta, actuatorDelta,
      `${label}: HTTP ${status} outlet mismatch`)
  }
  for (const reason of usageFailureReasons) {
    const actuatorDelta = counterDelta(after.usageFailures[reason], before.usageFailures[reason])
    const prometheusDelta = prometheusUsageFailureCount(afterPrometheus, reason)
      - prometheusUsageFailureCount(beforePrometheus, reason)
    assert.equal(prometheusDelta, actuatorDelta,
      `${label}: Usage failure outlet mismatch for ${reason}`)
  }
}

function createClient(name, fullApiKey) {
  return {
    name,
    fullApiKey,
    agent: new http.Agent({ keepAlive: true, maxSockets: maxInFlightPerApplication }),
    inFlight: 0,
  }
}

function uuidRequest(client, referenceAtEpochMs) {
  return new Promise((resolve) => {
    const started = performance.now()
    const request = http.request({
      protocol: apiOrigin.protocol,
      hostname: apiOrigin.hostname,
      port: apiOrigin.port,
      path: '/v1/utils/uuid',
      method: 'GET',
      agent: client.agent,
      headers: { Authorization: `ApiKey ${client.fullApiKey}` },
      timeout: requestTimeoutMs,
    }, (response) => {
      const chunks = []
      response.on('data', (chunk) => chunks.push(chunk))
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let code
        try { code = JSON.parse(text)?.code } catch { code = undefined }
        resolve({
          status: response.statusCode,
          code,
          durationMs: performance.now() - started,
          completedAtMs: Date.now() - referenceAtEpochMs,
        })
      })
    })
    request.on('timeout', () => request.destroy(new Error('client_timeout')))
    request.on('error', (error) => resolve({
      status: 0,
      code: error.message,
      durationMs: performance.now() - started,
      completedAtMs: Date.now() - referenceAtEpochMs,
    }))
    request.end()
  })
}

function startSegment(client, {
  name, rate, durationSeconds, startAtEpochMs, referenceAtEpochMs,
}) {
  const intervalMs = 1_000 / rate
  const planned = rate * durationSeconds
  const results = []
  let completed = 0
  let maxInFlightObserved = 0
  let abortReason
  const completion = new Promise((resolve) => {
    for (let index = 0; index < planned; index += 1) {
      const due = index * intervalMs
      setTimeout(async () => {
        const latenessMs = Date.now() - startAtEpochMs - due
        if (!abortReason && latenessMs > intervalMs) {
          abortReason = `scheduler_late:${latenessMs.toFixed(1)}ms`
        }
        if (!abortReason && client.inFlight >= maxInFlightPerApplication) {
          abortReason = `in_flight_limit:${client.inFlight}`
        }
        if (abortReason) {
          results.push({ status: -1, code: 'NOT_SENT', durationMs: 0,
            completedAtMs: Date.now() - referenceAtEpochMs, latenessMs })
        } else {
          client.inFlight += 1
          maxInFlightObserved = Math.max(maxInFlightObserved, client.inFlight)
          const result = await uuidRequest(client, referenceAtEpochMs)
          client.inFlight -= 1
          results.push({ ...result, latenessMs })
        }
        completed += 1
        if (completed === planned) resolve()
      }, Math.max(0, startAtEpochMs + due - Date.now()))
    }
  })
  return {
    name, client: client.name, rate, durationSeconds, planned, results,
    get abortReason() { return abortReason },
    get maxInFlightObserved() { return maxInFlightObserved },
    completion,
  }
}

function percentile(values, fraction) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * fraction))]
}

function summarizeSegment(segment) {
  const completed = segment.results.filter((item) => item.status > 0)
  const durations = completed.map((item) => item.durationMs)
  const successDurations = completed.filter((item) => item.status === 200)
    .map((item) => item.durationMs)
  const counts = Object.fromEntries([...new Set(segment.results.map(
    (item) => `${item.status}:${item.code ?? ''}`))].map((key) => [
    key, segment.results.filter((item) => `${item.status}:${item.code ?? ''}` === key).length,
  ]))
  const globalResults = segment.results.filter(
    (item) => item.status === 503 && item.code === 'RATE_LIMIT_GLOBAL_QUEUE_FULL')
  return {
    name: segment.name,
    client: segment.client,
    rate: segment.rate,
    durationSeconds: segment.durationSeconds,
    planned: segment.planned,
    abortReason: segment.abortReason,
    counts,
    completed200: segment.results.filter((item) => item.status === 200).length,
    completed429: segment.results.filter((item) => item.status === 429).length,
    completed503: segment.results.filter((item) => item.status === 503).length,
    globalQueueFull: globalResults.length,
    firstGlobalQueueFullAtMs: globalResults.length
      ? Math.min(...globalResults.map((item) => item.completedAtMs)) : null,
    unexpected: segment.results.filter((item) => ![200, 429, 503].includes(item.status)),
    latencyMs: {
      p50: percentile(durations, 0.50), p95: percentile(durations, 0.95),
      p99: percentile(durations, 0.99), max: durations.length ? Math.max(...durations) : null,
    },
    successLatencyMs: {
      p50: percentile(successDurations, 0.50), p95: percentile(successDurations, 0.95),
      p99: percentile(successDurations, 0.99),
      max: successDurations.length ? Math.max(...successDurations) : null,
    },
    maxInFlightObserved: segment.maxInFlightObserved,
    maxSchedulingLatenessMs: Math.max(...segment.results.map((item) => item.latenessMs)),
  }
}

async function runSenderWorker(data) {
  const client = createClient(data.name, data.fullApiKey)
  try {
    const segments = data.segments.map((segment) => startSegment(client, {
      ...segment,
      startAtEpochMs: data.commonStartEpochMs + segment.startOffsetMs,
      referenceAtEpochMs: data.commonStartEpochMs,
    }))
    await Promise.all(segments.map((segment) => segment.completion))
    return {
      kind: 'sender',
      role: data.name,
      threadId,
      segments: segments.map(summarizeSegment),
    }
  } finally {
    client.agent.destroy()
  }
}

async function observeWindow({
  startAtEpochMs, stopAtEpochMs, measurementStartEpochMs, scenarioName,
}) {
  const samples = []
  const activeSnapshotPromises = [5, 10, 15, 19.5].map(async (seconds) => {
    await sleepUntil(measurementStartEpochMs + seconds * 1_000)
    return prometheusSnapshot(scenarioName, `active-${String(seconds).replace('.', '-') }s`)
  })
  await sleepUntil(startAtEpochMs)
  let nextLimiter = 0
  let nextResources = 0
  let nextJvm = 0
  while (Date.now() < stopAtEpochMs) {
    const elapsed = Date.now() - startAtEpochMs
    const sample = { elapsedMs: elapsed }
    if (elapsed >= nextLimiter) {
      const [waiters, states] = await Promise.all([
        metricValue('nexus.ratelimit.waiters'), metricValue('nexus.ratelimit.states'),
      ])
      Object.assign(sample, { waiters, states })
      nextLimiter = elapsed + 100
    }
    if (elapsed >= nextResources) {
      const [hikariActive, hikariIdle, hikariPending, hikariMax,
        tomcatBusy, tomcatCurrent, tomcatMax, tomcatConnections] = await Promise.all([
        metricValue('hikaricp.connections.active'), metricValue('hikaricp.connections.idle'),
        metricValue('hikaricp.connections.pending'), metricValue('hikaricp.connections.max'),
        metricValue('tomcat.threads.busy'), metricValue('tomcat.threads.current'),
        metricValue('tomcat.threads.config.max'), metricValue('tomcat.connections.current'),
      ])
      Object.assign(sample, { hikariActive, hikariIdle, hikariPending, hikariMax,
        tomcatBusy, tomcatCurrent, tomcatMax, tomcatConnections })
      nextResources = elapsed + 500
    }
    if (elapsed >= nextJvm) {
      const [processCpu, heapUsed, heapMax, liveThreads] = await Promise.all([
        metricValue('process.cpu.usage'), metricValue('jvm.memory.used', { area: 'heap' }),
        metricValue('jvm.memory.max', { area: 'heap' }), metricValue('jvm.threads.live'),
      ])
      Object.assign(sample, { processCpu, heapUsed, heapMax, liveThreads })
      nextJvm = elapsed + 1_000
    }
    if (Object.keys(sample).length > 1) samples.push(sample)
    await sleep(30)
  }
  const activeSnapshots = await Promise.all(activeSnapshotPromises)
  return {
    kind: 'observer',
    role: 'metrics',
    threadId,
    samples,
    prometheus: activeSnapshots.map((item) => item.meta),
  }
}

async function runClockProbeWorker(data) {
  const intervalMs = 1_000 / data.rate
  const lateness = []
  await new Promise((resolve) => {
    for (let index = 0; index < data.ticks; index += 1) {
      const due = index * intervalMs
      setTimeout(() => {
        lateness.push(Date.now() - data.startAtEpochMs - due)
        if (lateness.length === data.ticks) resolve()
      }, Math.max(0, data.startAtEpochMs + due - Date.now()))
    }
  })
  return {
    kind: 'clock-probe',
    role: data.role,
    threadId,
    ticks: data.ticks,
    intervalMs,
    maxLatenessMs: Math.max(...lateness),
  }
}

async function runWorkerEntry() {
  try {
    let summary
    if (workerData.kind === 'sender') summary = await runSenderWorker(workerData)
    else if (workerData.kind === 'observer') summary = await observeWindow(workerData)
    else if (workerData.kind === 'clock-probe') summary = await runClockProbeWorker(workerData)
    else throw new Error(`Unknown worker kind: ${workerData.kind}`)
    parentPort.postMessage({ ok: true, summary })
  } catch (error) {
    parentPort.postMessage({
      ok: false,
      error: { name: error.name, message: error.message, stack: error.stack },
    })
  } finally {
    parentPort.close()
  }
}

function spawnExperimentWorker(data) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL(import.meta.url), {
      workerData: { ...data, runId, runDirectory },
    })
    let message
    worker.once('message', (value) => { message = value })
    worker.once('error', reject)
    worker.once('exit', (code) => {
      if (code !== 0) {
        reject(new Error(`${data.role ?? data.name} worker exited with code ${code}`))
      } else if (!message) {
        reject(new Error(`${data.role ?? data.name} worker exited without a summary`))
      } else if (!message.ok) {
        const failure = new Error(message.error.message)
        failure.name = message.error.name
        failure.stack = message.error.stack
        reject(failure)
      } else {
        resolve(message.summary)
      }
    })
  })
}

function requireWorkerResults(settled, label) {
  const failures = settled.filter((item) => item.status === 'rejected')
  assert.equal(failures.length, 0,
    `${label}: ${failures.map((item) => item.reason?.message ?? String(item.reason)).join('; ')}`)
  return settled.map((item) => item.value)
}

async function quota(resource, bearer) {
  return apiCall(`/api/application/${resource.applicationId}/quota`, {
    authorization: `Bearer ${bearer}`,
  })
}

async function usageCount(resource, bearer) {
  const query = new URLSearchParams({
    applicationId: String(resource.applicationId),
    apiKeyId: String(resource.apiKeyId),
    timeRange: 'TODAY',
  })
  const data = await apiCall(`/api/usage?${query}`, { authorization: `Bearer ${bearer}` })
  return data.allTimeCount
}

async function waitForUsage(resources, bearer, expectedCounts) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const actual = await Promise.all(resources.map((resource) => usageCount(resource, bearer)))
    for (let index = 0; index < actual.length; index += 1) {
      assert(actual[index] <= expectedCounts[index],
        `${resources[index].name} Usage exceeded the expected count`)
    }
    if (actual.every((value, index) => value === expectedCounts[index])) return
    await sleep(500)
  }
  throw new Error('Usage did not converge for every Application within 20 seconds')
}

async function waitForColdState() {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const [waiters, states] = await Promise.all([
      metricValue('nexus.ratelimit.waiters'), metricValue('nexus.ratelimit.states'),
    ])
    if (waiters === 0 && states === 0) return
    await sleep(250)
  }
  throw new Error('Rate limiter did not return to waiters=0 and states=0 within 25 seconds')
}

async function runScenario(group, scenario) {
  await waitForColdState()
  const usageBefore = await Promise.all(
    group.resources.map((resource) => usageCount(resource, group.bearer)))
  const quotaBefore = await Promise.all(
    group.resources.map((resource) => quota(resource, group.bearer)))
  const before = await boundarySnapshot()
  const beforePrometheus = await prometheusSnapshot(scenario.name, 'before')

  const warmupStartEpochMs = Date.now() + 1_500
  const measurementStartEpochMs = warmupStartEpochMs + warmupSeconds * 1_000
  const a = group.resources[0]
  const senderWorkerPromises = [spawnExperimentWorker({
    kind: 'sender',
    name: a.name,
    fullApiKey: a.fullApiKey,
    commonStartEpochMs: measurementStartEpochMs,
    segments: [
      { name: 'A-warmup', rate: aRate, durationSeconds: warmupSeconds,
        startOffsetMs: -warmupSeconds * 1_000 },
      { name: 'A-measurement', rate: aRate, durationSeconds: measurementSeconds,
        startOffsetMs: 0 },
    ],
  })]
  senderWorkerPromises.push(...group.resources.slice(1).map((resource, index) =>
    spawnExperimentWorker({
      kind: 'sender',
      name: resource.name,
      fullApiKey: resource.fullApiKey,
      commonStartEpochMs: measurementStartEpochMs,
      segments: [{
        name: `${resource.name}-measurement`,
        rate: bRate,
        durationSeconds: measurementSeconds,
        startOffsetMs: index * (100 / scenario.bCount),
      }],
    })))
  const observerWorkerPromise = spawnExperimentWorker({
    kind: 'observer',
    role: 'metrics',
    startAtEpochMs: warmupStartEpochMs,
    measurementStartEpochMs,
    stopAtEpochMs: measurementStartEpochMs
      + measurementSeconds * 1_000 + requestTimeoutMs + 250,
    scenarioName: scenario.name,
  })

  const settledWorkers = await Promise.allSettled([
    ...senderWorkerPromises, observerWorkerPromise,
  ])
  const workerSummaries = requireWorkerResults(settledWorkers, scenario.name)
  const senderWorkers = workerSummaries.slice(0, -1)
  const observerWorker = workerSummaries.at(-1)
  assert.equal(new Set(workerSummaries.map((worker) => worker.threadId)).size,
    workerSummaries.length, `${scenario.name}: workers did not use distinct threads`)
  const summaries = [
    ...senderWorkers[0].segments,
    ...senderWorkers.slice(1).map((worker) => worker.segments[0]),
  ]
  const clientCheckpoint = {
    scenario: scenario.name,
    capturedAt: new Date().toISOString(),
    commonStartEpochMs: measurementStartEpochMs,
    workerThreads: senderWorkers.map((worker) => ({
      role: worker.role, threadId: worker.threadId,
    })),
    warmupA: summaries[0],
    a: summaries[1],
    applicationsB: summaries.slice(2),
  }
  await writeFile(path.join(runDirectory, `${scenario.name}-client-summary.json`),
    JSON.stringify(clientCheckpoint, null, 2), 'utf8')
  for (const summary of summaries) {
    assertSegmentHealthy(summary, `${scenario.name}/${summary.name}`)
  }
  const expectedUsage = usageBefore.map((value, index) => {
    const resourceSummaries = index === 0 ? summaries.slice(0, 2) : [summaries[index + 1]]
    return value + resourceSummaries.reduce((sum, summary) => sum + summary.completed200, 0)
  })
  await waitForUsage(group.resources, group.bearer, expectedUsage)
  const quotaAfter = await Promise.all(
    group.resources.map((resource) => quota(resource, group.bearer)))
  for (let index = 0; index < group.resources.length; index += 1) {
    assert.equal(quotaAfter[index].usedCount - quotaBefore[index].usedCount,
      expectedUsage[index] - usageBefore[index],
      `${scenario.name}/${group.resources[index].name}: quota mismatch`)
  }
  await waitForColdState()
  const afterPrometheus = await prometheusSnapshot(scenario.name, 'after')
  const after = await boundarySnapshot()
  assertOutletAgreement(before, after, beforePrometheus.text, afterPrometheus.text,
    scenario.name)

  return {
    before,
    after,
    prometheus: [beforePrometheus.meta, ...observerWorker.prometheus,
      afterPrometheus.meta],
    workerIsolation: {
      commonStartEpochMs: measurementStartEpochMs,
      senders: senderWorkers.map((worker) => ({ role: worker.role, threadId: worker.threadId })),
      observer: { role: observerWorker.role, threadId: observerWorker.threadId },
    },
    observerSamples: observerWorker.samples,
    warmupA: summaries[0],
    a: summaries[1],
    applicationsB: summaries.slice(2),
    quotaBefore,
    quotaAfter,
  }
}

function maxSample(samples, property) {
  return Math.max(0, ...samples.map((sample) => Number(sample[property] ?? 0)))
}

function hasSustainedTomcatSaturation(samples) {
  let consecutive = 0
  for (const sample of samples) {
    if (sample.tomcatMax != null && sample.tomcatMax > 0
        && sample.tomcatBusy != null && sample.tomcatBusy >= sample.tomcatMax) {
      consecutive += 1
      if (consecutive >= 3) return true
    } else if (sample.tomcatBusy != null) {
      consecutive = 0
    }
  }
  return false
}

function usageFailureDelta(phase) {
  return usageFailureReasons.reduce((sum, reason) => sum
    + counterDelta(phase.after.usageFailures[reason], phase.before.usageFailures[reason]), 0)
}

function countClientCode(summaries, status, code) {
  return summaries.reduce((sum, summary) => sum + Number(summary.counts[`${status}:${code}`] ?? 0), 0)
}

function assertSegmentHealthy(segment, label) {
  assert.equal(segment.abortReason, undefined, `${label}: client invalidated the segment`)
  assert.equal(segment.unexpected.length, 0, `${label}: unexpected HTTP response or client error`)
  assert(segment.maxInFlightObserved < maxInFlightPerApplication,
    `${label}: reached client in-flight safety limit`)
  for (const key of Object.keys(segment.counts)) {
    const [statusText, code] = key.split(':')
    const status = Number(statusText)
    if (status === 200) assert.equal(code, 'SUCCESS', `${label}: unexpected 200 code`)
    if (status === 429) assert(['APPLICATION_RATE_QUEUE_FULL',
      'APPLICATION_RATE_WAIT_TIMEOUT'].includes(code), `${label}: unexpected 429 code ${code}`)
    if (status === 503) assert.equal(code, 'RATE_LIMIT_GLOBAL_QUEUE_FULL',
      `${label}: unexpected 503 code`)
  }
}

function assertScenario(phase, scenario) {
  const summaries = [phase.warmupA, phase.a, ...phase.applicationsB]
  for (const summary of summaries) assertSegmentHealthy(summary, `${scenario.name}/${summary.name}`)

  const expected200 = summaries.reduce((sum, summary) => sum + summary.completed200, 0)
  const expected429 = summaries.reduce((sum, summary) => sum + summary.completed429, 0)
  const expected503 = summaries.reduce((sum, summary) => sum + summary.completed503, 0)
  const admission = Object.fromEntries(admissionOutcomes.map((outcome) => [
    outcome, counterDelta(phase.after.admission[outcome], phase.before.admission[outcome]),
  ]))
  const admissionTotal = Object.values(admission).reduce((sum, value) => sum + value, 0)
  assert.equal(counterDelta(phase.after.http200, phase.before.http200), expected200,
    `${scenario.name}: HTTP 200 metric does not match clients`)
  assert.equal(counterDelta(phase.after.http429, phase.before.http429), expected429,
    `${scenario.name}: HTTP 429 metric does not match clients`)
  assert.equal(counterDelta(phase.after.http503, phase.before.http503), expected503,
    `${scenario.name}: HTTP 503 metric does not match clients`)
  assert.equal(admissionTotal, expected200 + expected429 + expected503,
    `${scenario.name}: admission count does not match clients`)
  assert.equal(admission.application_queue_full,
    countClientCode(summaries, 429, 'APPLICATION_RATE_QUEUE_FULL'),
  `${scenario.name}: application queue-full count mismatch`)
  assert.equal(admission.timed_out,
    countClientCode(summaries, 429, 'APPLICATION_RATE_WAIT_TIMEOUT'),
  `${scenario.name}: timeout count mismatch`)
  assert.equal(admission.global_queue_full,
    countClientCode(summaries, 503, 'RATE_LIMIT_GLOBAL_QUEUE_FULL'),
  `${scenario.name}: global queue-full count mismatch`)

  assert.equal(maxSample(phase.observerSamples, 'states'), scenario.bCount + 1,
    `${scenario.name}: unexpected rate-limit state count`)
  assert(maxSample(phase.observerSamples, 'waiters') <= globalWaiterCapacity,
    `${scenario.name}: global waiter capacity exceeded`)
  assert.equal(maxSample(phase.observerSamples, 'hikariPending'), 0,
    `${scenario.name}: Hikari became a competing bottleneck`)
  assert.equal(counterDelta(phase.after.hikariTimeout, phase.before.hikariTimeout), 0,
    `${scenario.name}: Hikari connection timeout increased`)
  assert.equal(usageFailureDelta(phase), 0,
    `${scenario.name}: Usage recording failure increased`)
  assert.equal(hasSustainedTomcatSaturation(phase.observerSamples), false,
    `${scenario.name}: Tomcat worker pool remained saturated`)
  for (const outcome of forbiddenAdmissionOutcomes) {
    assert.equal(admission[outcome], 0,
      `${scenario.name}: forbidden admission outcome ${outcome}`)
  }
  if (scenario.expectGlobalQueueFull) {
    assert(admission.global_queue_full > 0,
      `${scenario.name}: global queue capacity was not observed`)
  } else {
    assert.equal(admission.global_queue_full, 0,
      `${scenario.name}: negative control unexpectedly reached global queue capacity`)
  }
  return { admission, expected200, expected429, expected503 }
}

function mean(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

function assertWithinControlTolerance(control, treatment, label) {
  const controlBSuccess = mean(control.applicationsB.map((item) => item.completed200))
  const treatmentBSuccess = mean(treatment.applicationsB.map((item) => item.completed200))
  assert(treatmentBSuccess >= controlBSuccess * 0.90,
    `${label}: mean B success throughput fell by more than 10%`)
  const controlP95 = median(control.applicationsB.map((item) => item.successLatencyMs.p95))
  const treatmentP95 = median(treatment.applicationsB.map((item) => item.successLatencyMs.p95))
  assert(treatmentP95 <= controlP95 * 1.10,
    `${label}: median B successful-request p95 rose by more than 10%`)
  assert(treatment.a.completed200 >= control.a.completed200 * 0.90,
    `${label}: A success throughput fell by more than 10%`)
  return { controlBSuccess, treatmentBSuccess, controlP95, treatmentP95 }
}

async function createGroup(scenario, onProgress) {
  const suffix = randomBytes(8).toString('hex')
  const email = `obs-global-${scenario.name}-${suffix}@example.test`
  const password = randomBytes(24).toString('base64url')
  const group = { name: scenario.name, email, resources: [] }
  onProgress(group)
  await apiCall('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Global waiters ${suffix}`, password },
  })
  const login = await apiCall('/api/auth/login', { method: 'POST', body: { email, password } })
  group.bearer = login.accessToken
  const names = ['A', ...Array.from({ length: scenario.bCount }, (_, index) => `B${index + 1}`)]
  for (const name of names) {
    const application = await apiCall('/api/application/create', {
      method: 'POST', body: { name: `obs-global-${name.toLowerCase()}-${suffix}` },
      authorization: `Bearer ${group.bearer}`,
    })
    const key = await apiCall('/api/apiKey/create', {
      method: 'POST', body: { applicationId: application.id, name: `key-${name.toLowerCase()}-${suffix}` },
      authorization: `Bearer ${group.bearer}`,
    })
    const resource = {
      name,
      applicationId: application.id,
      apiKeyId: key.id,
      fullApiKey: key.apiKey,
      softDeleted: false,
    }
    group.resources.push(resource)
    const initialQuota = await quota(resource, group.bearer)
    assert.equal(initialQuota.quotaLimit, 1_000)
    assert.equal(initialQuota.usedCount, 0)
  }
  return group
}

function publicResources(group) {
  return {
    email: group.email,
    applications: group.resources.map((resource) => ({
      name: resource.name,
      applicationId: resource.applicationId,
      apiKeyId: resource.apiKeyId,
      softDeleted: resource.softDeleted,
    })),
  }
}

async function softDeleteGroup(group) {
  if (!group.bearer) return
  let firstFailure
  for (const resource of group.resources) {
    if (resource.softDeleted) continue
    try {
      await apiCall(`/api/application/${resource.applicationId}`, {
        method: 'DELETE', authorization: `Bearer ${group.bearer}`,
      })
      resource.softDeleted = true
    } catch (error) {
      firstFailure ??= error
    }
  }
  if (firstFailure) throw firstFailure
}

async function preflight() {
  const health = await jsonCall(managementOrigin, '/actuator/health')
  assert.equal(health.body?.status, 'UP')
  assert.equal(await metricValue('nexus.ratelimit.waiters'), 0)
  assert.equal(await metricValue('nexus.ratelimit.states'), 0)
  const unauthenticated = await jsonCall(apiOrigin, '/v1/utils/uuid', { expectOk: false })
  assert.equal(unauthenticated.status, 401,
    'Unauthenticated metric-registration probe must return 401')
  const metricNames = (await jsonCall(managementOrigin, '/actuator/metrics')).body?.names ?? []
  const requiredMetricNames = [
    'nexus.ratelimit.admission', 'nexus.ratelimit.waiters', 'nexus.ratelimit.states',
    'nexus.usage.record.failures', 'http.server.requests',
    'hikaricp.connections.active', 'hikaricp.connections.idle',
    'hikaricp.connections.pending', 'hikaricp.connections.max',
    'hikaricp.connections.acquire', 'hikaricp.connections.timeout',
    'process.cpu.usage', 'system.cpu.usage', 'jvm.memory.used', 'jvm.memory.max',
    'jvm.threads.live', 'tomcat.threads.busy', 'tomcat.threads.current',
    'tomcat.threads.config.max', 'tomcat.connections.current',
    'tomcat.connections.config.max',
  ]
  const missingMetricNames = requiredMetricNames.filter((name) => !metricNames.includes(name))
  assert.deepEqual(missingMetricNames, [],
    `Required metrics missing: ${missingMetricNames.join(', ')}. `
    + 'Start only the experiment JVM with SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true.')
  assert.equal(await metricValue('hikaricp.connections.max'), 10,
    'Experiment Hikari maximum must be 10')
  const prometheus = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(prometheus.status, 200, 'Prometheus endpoint must return 200')
  const prometheusText = await prometheus.text()
  assert(prometheusText.includes('nexus_ratelimit_waiters '),
    'Prometheus endpoint is missing nexus_ratelimit_waiters')
  assert(prometheusText.includes('tomcat_threads_busy_threads'),
    'Prometheus endpoint is missing Tomcat thread metrics')
  return { health: 'UP', waiters: 0, states: 0, requiredMetricNames }
}

async function workerIsolationPreflight() {
  const roles = [
    { role: 'A', rate: aRate },
    ...Array.from({ length: 10 }, (_, index) => ({ role: `B${index + 1}`, rate: bRate })),
    { role: 'metrics', rate: 10 },
  ]
  const commonStartEpochMs = Date.now() + 1_500
  const settled = await Promise.allSettled(roles.map((role) => spawnExperimentWorker({
    kind: 'clock-probe',
    ...role,
    startAtEpochMs: commonStartEpochMs,
    ticks: role.role === 'A' ? 40 : 20,
  })))
  const workers = requireWorkerResults(settled, 'worker-isolation-preflight')
  assert.equal(new Set(workers.map((worker) => worker.threadId)).size, roles.length,
    'Every sender and observer must use a distinct worker thread')
  for (const worker of workers) {
    assert(worker.maxLatenessMs <= worker.intervalMs,
      `${worker.role}: clock probe fell behind by more than one interval`)
  }
  return {
    commonStartEpochMs,
    workerCount: workers.length,
    workers,
    maxLatenessMs: Math.max(...workers.map((worker) => worker.maxLatenessMs)),
  }
}

async function runCoordinator() {
  if (process.argv.includes('--worker-preflight')) {
    const workerPreflight = await workerIsolationPreflight()
    console.log(JSON.stringify(workerPreflight, null, 2))
    return
  }

  await mkdir(runDirectory, { recursive: true })
  const result = {
    runId,
    startedAt: new Date().toISOString(),
    configuration: {
      totalApplicationsInTreatment: 11,
      aRate,
      bRate,
      warmupSeconds,
      measurementSeconds,
      maxInFlightPerApplication,
      treatmentClientSafetyCeiling: 11 * maxInFlightPerApplication,
      requestTimeoutMs,
      globalWaiterCapacity,
      clientIsolation: {
        senderWorkers: 11,
        observerWorkers: 1,
        sharedEventLoop: false,
        sharedHttpAgent: false,
      },
      scenarios,
    },
    groups: [],
  }
  let activeGroup
  let failed
  try {
    result.workerIsolationPreflight = await workerIsolationPreflight()
    result.preflight = await preflight()
    for (const scenario of scenarios) {
      activeGroup = undefined
      const group = await createGroup(scenario, (progress) => { activeGroup = progress })
      const groupResult = { name: scenario.name, scenario, resources: publicResources(group) }
      try {
        groupResult.phase = await runScenario(group, scenario)
        groupResult.accounting = assertScenario(groupResult.phase, scenario)
        groupResult.accepted = true
        console.log(`${scenario.name}: 200/429/503=${groupResult.accounting.expected200}/${groupResult.accounting.expected429}/${groupResult.accounting.expected503}; global_queue_full=${groupResult.accounting.admission.global_queue_full}; max_waiters=${maxSample(groupResult.phase.observerSamples, 'waiters')}`)
      } finally {
        await softDeleteGroup(group)
        groupResult.resources = publicResources(group)
        result.groups.push(groupResult)
        await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
        const cleanupPairs = groupResult.resources.applications
          .map((resource) => `${resource.applicationId}:${resource.apiKeyId}`).join(' ')
        console.log(`CLEANUP_BATCH ${groupResult.resources.email} ${cleanupPairs}`)
        activeGroup = undefined
      }
    }
    const control = result.groups[0].phase
    result.treatmentComparisons = result.groups.slice(1).map((group) => ({
      name: group.name,
      ...assertWithinControlTolerance(control, group.phase, group.name),
    }))
    assert.equal(result.groups.slice(1).filter(
      (group) => group.accounting.admission.global_queue_full > 0).length, 3,
    'Global queue-full result was not repeatable in all three treatment runs')
    result.completedAt = new Date().toISOString()
  } catch (error) {
    failed = error
    result.failedAt = new Date().toISOString()
    result.failure = { name: error.name, message: error.message }
  } finally {
    if (activeGroup) {
      try { await softDeleteGroup(activeGroup) } catch (error) {
        result.softDeleteFailure = error.message
        failed ??= error
      } finally {
        result.groups.push({ name: activeGroup.name, resources: publicResources(activeGroup), interrupted: true })
      }
    }
    await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
    console.log(`RESULT_FILE=${path.join(runDirectory, 'result.json')}`)
  }
  if (failed) throw failed
}

if (isMainThread) await runCoordinator()
else await runWorkerEntry()
