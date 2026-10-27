import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { performance } from 'node:perf_hooks'
import path from 'node:path'

const apiOrigin = new URL(process.env.NEXUS_EXPERIMENT_API_ORIGIN ?? 'http://127.0.0.1:8080')
const managementOrigin = new URL(process.env.NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN ?? 'http://127.0.0.1:8081')
const outputRoot = process.env.NEXUS_EXPERIMENT_OUTPUT_DIR
  ?? path.resolve('target', 'multi-application-isolation-experiment')

const bRates = [1, 5, 10]
const aRate = 20
const warmupSeconds = 2
const measurementSeconds = 10
const maxInFlightPerApplication = 64
const requestTimeoutMs = 3_000
const runId = `obs-multi-app-${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}`
const runDirectory = path.join(outputRoot, runId)

const admissionOutcomes = [
  'allowed_immediate', 'allowed_after_wait', 'application_queue_full',
  'global_queue_full', 'state_capacity_reached', 'timed_out', 'cancelled', 'unavailable',
]
const usageFailureReasons = [
  'queue_rejected', 'transient_write_exhausted', 'duplicate_unconfirmed', 'write_failed',
]
const forbiddenAdmissionOutcomes = [
  'global_queue_full', 'state_capacity_reached', 'cancelled', 'unavailable',
]

await mkdir(runDirectory, { recursive: true })

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

async function sleepUntil(target) {
  const remaining = target - performance.now()
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
    hikariAcquire: await timerSnapshot('hikaricp.connections.acquire'),
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

async function prometheusSnapshot(groupName, phaseName, point) {
  const response = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(response.status, 200, 'Prometheus endpoint must return 200')
  const text = await response.text()
  const filename = `${groupName}-${phaseName}-${point}-prometheus.txt`
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
  const actuator200 = counterDelta(after.http200, before.http200)
  const actuator429 = counterDelta(after.http429, before.http429)
  assert.equal(prometheusHttpCount(afterPrometheus, '200', '/v1/utils/uuid')
    - prometheusHttpCount(beforePrometheus, '200', '/v1/utils/uuid'), actuator200,
  `${label}: HTTP 200 outlet mismatch`)
  assert.equal(prometheusHttpCount(afterPrometheus, '429', 'UNKNOWN')
    - prometheusHttpCount(beforePrometheus, '429', 'UNKNOWN'), actuator429,
  `${label}: HTTP 429 outlet mismatch`)
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

function uuidRequest(client) {
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

function startSegment(client, { name, rate, durationSeconds, startAt }) {
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
        const latenessMs = performance.now() - startAt - due
        if (!abortReason && latenessMs > intervalMs) {
          abortReason = `scheduler_late:${latenessMs.toFixed(1)}ms`
        }
        if (!abortReason && client.inFlight >= maxInFlightPerApplication) {
          abortReason = `in_flight_limit:${client.inFlight}`
        }
        if (abortReason) {
          results.push({ status: -1, code: 'NOT_SENT', durationMs: 0, latenessMs })
        } else {
          client.inFlight += 1
          maxInFlightObserved = Math.max(maxInFlightObserved, client.inFlight)
          const result = await uuidRequest(client)
          client.inFlight -= 1
          results.push({ ...result, latenessMs })
        }
        completed += 1
        if (completed === planned) resolve()
      }, Math.max(0, startAt + due - performance.now()))
    }
  })
  return { name, client: client.name, rate, durationSeconds, planned, results,
    get abortReason() { return abortReason },
    get maxInFlightObserved() { return maxInFlightObserved }, completion }
}

function summarizeSegment(segment) {
  const completed = segment.results.filter((item) => item.status > 0)
  const durations = completed.map((item) => item.durationMs).sort((a, b) => a - b)
  const percentile = (fraction) => durations.length
    ? durations[Math.min(durations.length - 1, Math.floor((durations.length - 1) * fraction))]
    : null
  const counts = Object.fromEntries([...new Set(segment.results.map(
    (item) => `${item.status}:${item.code ?? ''}`))].map((key) => [
    key, segment.results.filter((item) => `${item.status}:${item.code ?? ''}` === key).length,
  ]))
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
    unexpected: segment.results.filter((item) => ![200, 429].includes(item.status)),
    latencyMs: {
      p50: percentile(0.50), p95: percentile(0.95),
      p99: percentile(0.99), max: durations.at(-1) ?? null,
    },
    maxInFlightObserved: segment.maxInFlightObserved,
    maxSchedulingLatenessMs: Math.max(...segment.results.map((item) => item.latenessMs)),
  }
}

function startObserver(startedAt) {
  const samples = []
  let stopped = false
  const loop = (async () => {
    let nextLimiter = 0
    let nextResources = 0
    let nextJvm = 0
    while (!stopped) {
      const elapsed = performance.now() - startedAt
      if (elapsed < 0) {
        await sleep(Math.min(40, -elapsed))
        continue
      }
      const sample = { elapsedMs: elapsed }
      if (elapsed >= nextLimiter) {
        const [waiters, states] = await Promise.all([
          metricValue('nexus.ratelimit.waiters'), metricValue('nexus.ratelimit.states'),
        ])
        Object.assign(sample, { waiters, states })
        nextLimiter += 200
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
        nextResources += 500
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
  return { samples, async stop() { stopped = true; await loop } }
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

async function waitForUsage(resource, bearer, expected) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (await usageCount(resource, bearer) === expected) return
    await sleep(250)
  }
  throw new Error(`${resource.name} Usage did not converge to ${expected} within 20 seconds`)
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

async function runControl(group, bRate) {
  await waitForColdState()
  const usageBefore = await usageCount(group.b, group.bearer)
  const quotaBefore = await quota(group.b, group.bearer)
  const before = await boundarySnapshot()
  const beforePrometheus = await prometheusSnapshot(group.name, 'control', 'before')
  const startAt = performance.now() + 250
  const observer = startObserver(startAt)
  const segment = startSegment(group.b.client, {
    name: 'B-control', rate: bRate, durationSeconds: measurementSeconds, startAt,
  })
  const midpointPromise = (async () => {
    await sleepUntil(startAt + measurementSeconds * 500)
    return prometheusSnapshot(group.name, 'control', 'mid')
  })()
  await segment.completion
  await observer.stop()
  const midpoint = await midpointPromise
  const summary = summarizeSegment(segment)
  await waitForUsage(group.b, group.bearer, usageBefore + summary.completed200)
  const quotaAfter = await quota(group.b, group.bearer)
  assert.equal(quotaAfter.usedCount - quotaBefore.usedCount, summary.completed200,
    `${group.name}/control B quota mismatch`)
  await waitForColdState()
  const afterPrometheus = await prometheusSnapshot(group.name, 'control', 'after')
  const after = await boundarySnapshot()
  assertOutletAgreement(before, after, beforePrometheus.text, afterPrometheus.text,
    `${group.name}/control`)
  return { before, after, prometheus: [beforePrometheus.meta, midpoint.meta, afterPrometheus.meta],
    observerSamples: observer.samples, b: summary, quotaBefore, quotaAfter }
}

async function runTreatment(group, bRate) {
  await waitForColdState()
  const usageBeforeA = await usageCount(group.a, group.bearer)
  const usageBeforeB = await usageCount(group.b, group.bearer)
  const quotaBeforeA = await quota(group.a, group.bearer)
  const quotaBeforeB = await quota(group.b, group.bearer)
  const before = await boundarySnapshot()
  const beforePrometheus = await prometheusSnapshot(group.name, 'treatment', 'before')

  const warmupStart = performance.now() + 250
  const measurementStart = warmupStart + warmupSeconds * 1_000
  const warmupA = startSegment(group.a.client, {
    name: 'A-warmup', rate: aRate, durationSeconds: warmupSeconds, startAt: warmupStart,
  })
  const measuredA = startSegment(group.a.client, {
    name: 'A-treatment', rate: aRate, durationSeconds: measurementSeconds, startAt: measurementStart,
  })
  const measuredB = startSegment(group.b.client, {
    name: 'B-treatment', rate: bRate, durationSeconds: measurementSeconds, startAt: measurementStart,
  })
  const observer = startObserver(measurementStart)
  const midpointPromise = (async () => {
    await sleepUntil(measurementStart + measurementSeconds * 500)
    return prometheusSnapshot(group.name, 'treatment', 'mid')
  })()

  await Promise.all([warmupA.completion, measuredA.completion, measuredB.completion])
  await observer.stop()
  const midpoint = await midpointPromise
  const warmupSummary = summarizeSegment(warmupA)
  const aSummary = summarizeSegment(measuredA)
  const bSummary = summarizeSegment(measuredB)
  const expectedA = usageBeforeA + warmupSummary.completed200 + aSummary.completed200
  const expectedB = usageBeforeB + bSummary.completed200
  await Promise.all([
    waitForUsage(group.a, group.bearer, expectedA),
    waitForUsage(group.b, group.bearer, expectedB),
  ])
  const [quotaAfterA, quotaAfterB] = await Promise.all([
    quota(group.a, group.bearer), quota(group.b, group.bearer),
  ])
  assert.equal(quotaAfterA.usedCount - quotaBeforeA.usedCount,
    warmupSummary.completed200 + aSummary.completed200, `${group.name}/treatment A quota mismatch`)
  assert.equal(quotaAfterB.usedCount - quotaBeforeB.usedCount,
    bSummary.completed200, `${group.name}/treatment B quota mismatch`)
  await waitForColdState()
  const afterPrometheus = await prometheusSnapshot(group.name, 'treatment', 'after')
  const after = await boundarySnapshot()
  assertOutletAgreement(before, after, beforePrometheus.text, afterPrometheus.text,
    `${group.name}/treatment`)
  return { before, after, prometheus: [beforePrometheus.meta, midpoint.meta, afterPrometheus.meta],
    observerSamples: observer.samples, warmupA: warmupSummary, a: aSummary, b: bSummary,
    quotaBeforeA, quotaAfterA, quotaBeforeB, quotaAfterB }
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

function assertSegmentHealthy(segment, label) {
  assert.equal(segment.abortReason, undefined, `${label}: client invalidated the segment`)
  assert.equal(segment.unexpected.length, 0, `${label}: unexpected HTTP response or client error`)
  assert(segment.maxInFlightObserved < maxInFlightPerApplication,
    `${label}: reached client in-flight safety limit`)
}

function assertPhaseAccounting(phase, segments, label) {
  const expected200 = segments.reduce((sum, segment) => sum + segment.completed200, 0)
  const expected429 = segments.reduce((sum, segment) => sum + segment.completed429, 0)
  const admissionCount = admissionOutcomes.reduce((sum, outcome) => sum
    + counterDelta(phase.after.admission[outcome], phase.before.admission[outcome]), 0)
  assert.equal(counterDelta(phase.after.http200, phase.before.http200), expected200,
    `${label}: HTTP 200 metric does not match clients`)
  assert.equal(counterDelta(phase.after.http429, phase.before.http429), expected429,
    `${label}: HTTP 429 metric does not match clients`)
  assert.equal(admissionCount, expected200 + expected429,
    `${label}: admission count does not match completed requests`)
}

function assertPhaseInfrastructure(phase, expectedMaxStates, waiterLimit, label) {
  assert.equal(maxSample(phase.observerSamples, 'states'), expectedMaxStates,
    `${label}: unexpected rate-limit state count`)
  assert(maxSample(phase.observerSamples, 'waiters') <= waiterLimit,
    `${label}: waiter bound exceeded`)
  assert.equal(maxSample(phase.observerSamples, 'hikariPending'), 0,
    `${label}: Hikari became a competing bottleneck`)
  assert.equal(usageFailureDelta(phase), 0, `${label}: Usage recording failure increased`)
  assert.equal(hasSustainedTomcatSaturation(phase.observerSamples), false,
    `${label}: Tomcat worker pool remained saturated`)
  for (const outcome of forbiddenAdmissionOutcomes) {
    assert.equal(counterDelta(phase.after.admission[outcome], phase.before.admission[outcome]), 0,
      `${label}: forbidden admission outcome ${outcome}`)
  }
}

function assertIsolation(control, treatment, bRate, label) {
  assertSegmentHealthy(control.b, `${label}/control-B`)
  assertSegmentHealthy(treatment.warmupA, `${label}/warmup-A`)
  assertSegmentHealthy(treatment.a, `${label}/treatment-A`)
  assertSegmentHealthy(treatment.b, `${label}/treatment-B`)
  assertPhaseAccounting(control, [control.b], `${label}/control`)
  assertPhaseAccounting(treatment, [treatment.warmupA, treatment.a, treatment.b],
    `${label}/treatment`)
  assertPhaseInfrastructure(control, 1, 10, `${label}/control`)
  assertPhaseInfrastructure(treatment, 2, bRate < 10 ? 10 : 20, `${label}/treatment`)

  if (bRate < 10) {
    assert.equal(control.b.completed429, 0, `${label}: control B unexpectedly rate-limited`)
    assert.equal(treatment.b.completed429, 0, `${label}: treatment B unexpectedly rate-limited`)
    assert.equal(control.b.completed200, control.b.planned, `${label}: control B did not all succeed`)
    assert.equal(treatment.b.completed200, treatment.b.planned, `${label}: treatment B did not all succeed`)
  }
  assert(treatment.b.completed200 >= control.b.completed200 * 0.90,
    `${label}: treatment B throughput fell by more than 10%`)
  if (bRate === 10 && control.b.latencyMs.p95 != null) {
    assert(treatment.b.latencyMs.p95 <= control.b.latencyMs.p95 * 1.10,
      `${label}: treatment B p95 rose by more than 10%`)
  }
}

async function createGroup(bRate) {
  const suffix = randomBytes(8).toString('hex')
  const email = `obs-multi-${bRate}-${suffix}@example.test`
  const password = randomBytes(24).toString('base64url')
  await apiCall('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Multi app ${bRate} ${suffix}`, password },
  })
  const login = await apiCall('/api/auth/login', { method: 'POST', body: { email, password } })
  const bearer = login.accessToken
  const appA = await apiCall('/api/application/create', {
    method: 'POST', body: { name: `obs-multi-a-${bRate}-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  const appB = await apiCall('/api/application/create', {
    method: 'POST', body: { name: `obs-multi-b-${bRate}-${suffix}` }, authorization: `Bearer ${bearer}`,
  })
  const keyA = await apiCall('/api/apiKey/create', {
    method: 'POST', body: { applicationId: appA.id, name: `key-a-${suffix}` },
    authorization: `Bearer ${bearer}`,
  })
  const keyB = await apiCall('/api/apiKey/create', {
    method: 'POST', body: { applicationId: appB.id, name: `key-b-${suffix}` },
    authorization: `Bearer ${bearer}`,
  })
  const group = {
    name: `b${bRate}rps`, bRate, email, bearer,
    a: { name: 'A', applicationId: appA.id, apiKeyId: keyA.id,
      client: createClient('A', keyA.apiKey) },
    b: { name: 'B', applicationId: appB.id, apiKeyId: keyB.id,
      client: createClient('B', keyB.apiKey) },
    softDeletedA: false,
    softDeletedB: false,
  }
  const [quotaA, quotaB] = await Promise.all([quota(group.a, bearer), quota(group.b, bearer)])
  assert.equal(quotaA.quotaLimit, 1_000)
  assert.equal(quotaA.usedCount, 0)
  assert.equal(quotaB.quotaLimit, 1_000)
  assert.equal(quotaB.usedCount, 0)
  return group
}

function publicResources(group) {
  return {
    email: group.email,
    applicationAId: group.a.applicationId,
    apiKeyAId: group.a.apiKeyId,
    applicationBId: group.b.applicationId,
    apiKeyBId: group.b.apiKeyId,
    softDeletedA: group.softDeletedA,
    softDeletedB: group.softDeletedB,
  }
}

async function softDeleteGroup(group) {
  if (!group.softDeletedA) {
    await apiCall(`/api/application/${group.a.applicationId}`, {
      method: 'DELETE', authorization: `Bearer ${group.bearer}`,
    })
    group.softDeletedA = true
  }
  if (!group.softDeletedB) {
    await apiCall(`/api/application/${group.b.applicationId}`, {
      method: 'DELETE', authorization: `Bearer ${group.bearer}`,
    })
    group.softDeletedB = true
  }
}

function closeGroupClients(group) {
  group.a.client.agent.destroy()
  group.b.client.agent.destroy()
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
    'hikaricp.connections.acquire', 'process.cpu.usage', 'system.cpu.usage',
    'jvm.memory.used', 'jvm.memory.max', 'jvm.threads.live',
    'tomcat.threads.busy', 'tomcat.threads.current', 'tomcat.threads.config.max',
    'tomcat.connections.current', 'tomcat.connections.config.max',
  ]
  const missingMetricNames = requiredMetricNames.filter((name) => !metricNames.includes(name))
  assert.deepEqual(missingMetricNames, [],
    `Required metrics missing: ${missingMetricNames.join(', ')}. `
    + 'For Tomcat pool metrics, start only the experiment JVM with '
    + 'SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true.')
  const prometheus = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(prometheus.status, 200, 'Prometheus endpoint must return 200')
  const prometheusText = await prometheus.text()
  assert(prometheusText.includes('nexus_ratelimit_waiters '),
    'Prometheus endpoint is missing nexus_ratelimit_waiters')
  assert(prometheusText.includes('nexus_ratelimit_states '),
    'Prometheus endpoint is missing nexus_ratelimit_states')
  return {
    health: 'UP', waiters: 0, states: 0,
    requiredMetricNames,
    tomcatMetrics: metricNames.filter((name) => name.startsWith('tomcat.')),
  }
}

const result = {
  runId,
  startedAt: new Date().toISOString(),
  configuration: { aRate, bRates, warmupSeconds, measurementSeconds,
    maxInFlightPerApplication, requestTimeoutMs },
  groups: [],
}
let activeGroup
let failed
try {
  result.preflight = await preflight()
  for (const bRate of bRates) {
    activeGroup = await createGroup(bRate)
    const groupResult = { name: activeGroup.name, bRate,
      resources: publicResources(activeGroup) }
    try {
      groupResult.control = await runControl(activeGroup, bRate)
      groupResult.treatment = await runTreatment(activeGroup, bRate)
      assertIsolation(groupResult.control, groupResult.treatment, bRate, activeGroup.name)
      groupResult.accepted = true
      console.log(`${activeGroup.name}: control B 200/429=${groupResult.control.b.completed200}/${groupResult.control.b.completed429}; treatment B 200/429=${groupResult.treatment.b.completed200}/${groupResult.treatment.b.completed429}`)
    } finally {
      try { await softDeleteGroup(activeGroup) } finally { closeGroupClients(activeGroup) }
      groupResult.resources = publicResources(activeGroup)
      result.groups.push(groupResult)
      await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
      console.log(`CLEANUP_PAIR ${groupResult.resources.applicationAId} ${groupResult.resources.apiKeyAId} ${groupResult.resources.applicationBId} ${groupResult.resources.apiKeyBId} ${groupResult.resources.email}`)
      activeGroup = undefined
    }
  }
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
      closeGroupClients(activeGroup)
      result.groups.push({ name: activeGroup.name, bRate: activeGroup.bRate,
        resources: publicResources(activeGroup), interrupted: true })
    }
  }
  await writeFile(path.join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), 'utf8')
  console.log(`RESULT_FILE=${path.join(runDirectory, 'result.json')}`)
}
if (failed) throw failed
