import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { promisify } from 'node:util'
import { performance } from 'node:perf_hooks'
import path from 'node:path'

const execFileAsync = promisify(execFile)
const apiOrigin = new URL(process.env.NEXUS_EXPERIMENT_API_ORIGIN ?? 'http://127.0.0.1:18180')
const managementOrigin = new URL(process.env.NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN ?? 'http://127.0.0.1:18181')
const outputRoot = process.env.NEXUS_EXPERIMENT_OUTPUT_DIR
  ?? path.resolve('target', 'screenshot-backpressure-experiment')
const serverPid = Number(process.env.NEXUS_EXPERIMENT_SERVER_PID)
const browserRoot = process.env.NEXUS_EXPERIMENT_BROWSER_ROOT ?? ''
const fixtureUrl = 'https://blog.csdn.net/nexus-controlled-screenshot-experiment'
const requestTimeoutMs = 3_000
const maxInFlight = 16
// ScreenshotJobScheduler uses scheduleWithFixedDelay(..., 0, 1, SECONDS).
// Gate occupancy describes Chromium resource time; queue drainage also includes
// this scheduler delay after each completed worker invocation.
const workerFixedDelaySeconds = 1
const unfinishedCapacity = 10
const mode = process.argv.includes('--load') ? 'load'
  : process.argv.includes('--calibrate') ? 'calibrate' : 'preflight'
const loadRate = Number(process.env.NEXUS_SCREENSHOT_EXPERIMENT_RATE_RPS ?? 0)
const durationSeconds = Number(process.env.NEXUS_SCREENSHOT_EXPERIMENT_DURATION_SECONDS ?? 30)
const backpressureExpectation =
  process.env.NEXUS_SCREENSHOT_BACKPRESSURE_EXPECTATION ?? 'observe'
const loadApproval = process.env.NEXUS_SCREENSHOT_LOAD_APPROVAL
const runId = `obs-screenshot-${mode}-${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}`
const runDirectory = path.join(outputRoot, runId)
const agent = new http.Agent({ keepAlive: true, maxSockets: maxInFlight })
const suffix = randomBytes(8).toString('hex')
const email = `obs-screenshot-${suffix}@example.test`
const password = randomBytes(24).toString('base64url')
const usageFailureReasons = [
  'queue_rejected', 'transient_write_exhausted', 'duplicate_unconfirmed', 'write_failed',
]
const admissionOutcomes = [
  'allowed_immediate', 'allowed_after_wait', 'application_queue_full',
  'global_queue_full', 'state_capacity_reached', 'timed_out', 'cancelled', 'unavailable',
]

if (!Number.isInteger(serverPid) || serverPid <= 0) {
  throw new Error('NEXUS_EXPERIMENT_SERVER_PID must identify the controlled server process')
}
if (!path.isAbsolute(browserRoot)) {
  throw new Error('NEXUS_EXPERIMENT_BROWSER_ROOT must be an absolute path')
}
if (mode === 'load') {
  assert.equal(loadApproval, 'RUN_APPROVED_SCREENSHOT_LOAD',
    'Real load requires NEXUS_SCREENSHOT_LOAD_APPROVAL=RUN_APPROVED_SCREENSHOT_LOAD')
  assert(Number.isFinite(loadRate) && loadRate > 0 && loadRate <= 5,
    'Load rate must be in (0, 5] RPS so Application rate limiting is not the treatment')
  assert(Number.isInteger(durationSeconds) && durationSeconds >= 10 && durationSeconds <= 60,
    'Load duration must be an integer from 10 to 60 seconds')
  assert(['forbidden', 'observe', 'required'].includes(backpressureExpectation),
    'Backpressure expectation must be forbidden, observe, or required')
}

await mkdir(runDirectory, { recursive: true })

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
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
  return {
    status: response.status,
    headers: Object.fromEntries(response.headers.entries()),
    body: parsed,
    text,
  }
}

async function apiCall(requestPath, options = {}) {
  return (await jsonCall(apiOrigin, requestPath, options)).body?.data
}

async function metric(name, tags = {}) {
  const url = new URL(`/actuator/metrics/${name}`, managementOrigin)
  for (const [key, value] of Object.entries(tags)) {
    url.searchParams.append('tag', `${key}:${value}`)
  }
  const response = await fetch(url, { signal: AbortSignal.timeout(requestTimeoutMs) })
  if (response.status === 404) return null
  assert.equal(response.status, 200, `Metric ${name} returned ${response.status}`)
  return response.json()
}

function measurements(value) {
  return Object.fromEntries((value?.measurements ?? []).map(
    (item) => [item.statistic, item.value]))
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

async function boundarySnapshot() {
  const admission = Object.fromEntries(await Promise.all(admissionOutcomes.map(
    async (outcome) => [outcome, await timerSnapshot(
      'nexus.ratelimit.admission', { outcome })])))
  const usageFailures = Object.fromEntries(await Promise.all(usageFailureReasons.map(
    async (reason) => [reason, measurements(await metric(
      'nexus.usage.record.failures', { reason }))])))
  return {
    at: new Date().toISOString(),
    screenshot: {
      unfinished: await metricValue('nexus.screenshot.jobs.unfinished'),
      slotBusy: await metricValue('nexus.screenshot.slot.busy'),
      queueDelay: await timerSnapshot('nexus.screenshot.queue.delay'),
      executionAsyncSucceeded: await timerSnapshot('nexus.screenshot.execution', {
        mode: 'async', outcome: 'succeeded',
      }),
      executionAsyncFailed: await timerSnapshot('nexus.screenshot.execution', {
        mode: 'async', outcome: 'failed',
      }),
    },
    admission,
    rateLimiter: {
      waiters: await metricValue('nexus.ratelimit.waiters'),
      states: await metricValue('nexus.ratelimit.states'),
    },
    http: {
      created: await timerSnapshot('http.server.requests', {
        method: 'POST', uri: '/v1/web/screenshot-jobs', status: '201',
      }),
      queueFull: await timerSnapshot('http.server.requests', {
        method: 'POST', uri: '/v1/web/screenshot-jobs', status: '503',
      }),
      rateLimited: await timerSnapshot('http.server.requests', {
        method: 'POST', uri: 'UNKNOWN', status: '429',
      }),
    },
    hikari: {
      active: await metricValue('hikaricp.connections.active'),
      idle: await metricValue('hikaricp.connections.idle'),
      pending: await metricValue('hikaricp.connections.pending'),
      maximum: await metricValue('hikaricp.connections.max'),
      acquire: await timerSnapshot('hikaricp.connections.acquire'),
      timeout: measurements(await metric('hikaricp.connections.timeout')),
    },
    tomcat: {
      busy: await metricValue('tomcat.threads.busy'),
      current: await metricValue('tomcat.threads.current'),
      maximum: await metricValue('tomcat.threads.config.max'),
      connections: await metricValue('tomcat.connections.current'),
    },
    jvm: {
      processCpu: await metricValue('process.cpu.usage'),
      systemCpu: await metricValue('system.cpu.usage'),
      heapUsed: await metricValue('jvm.memory.used', { area: 'heap' }),
      heapMax: await metricValue('jvm.memory.max', { area: 'heap' }),
      liveThreads: await metricValue('jvm.threads.live'),
      peakThreads: await metricValue('jvm.threads.peak'),
      gcPause: await timerSnapshot('jvm.gc.pause'),
      diskFree: await metricValue('disk.free'),
    },
    usageFailures,
  }
}

async function prometheusSnapshot(label) {
  const response = await fetch(new URL('/actuator/prometheus', managementOrigin), {
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(response.status, 200, 'Prometheus endpoint must return 200')
  const text = await response.text()
  const filename = `${label}-prometheus.txt`
  await writeFile(path.join(runDirectory, filename), text, 'utf8')
  return { label, at: new Date().toISOString(), filename, text }
}

function prometheusValue(text, metricName, labels = {}) {
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith(metricName)) continue
    if (!Object.entries(labels).every(
      ([key, value]) => line.includes(`${key}="${value}"`))) continue
    const value = Number(line.slice(line.lastIndexOf(' ') + 1))
    if (Number.isFinite(value)) return value
  }
  return 0
}

function assertDualOutletAgreement(before, after, beforePrometheus, afterPrometheus) {
  const checks = [
    ['HTTP 201', counterDelta(after.http.created, before.http.created),
      'http_server_requests_seconds_count',
      { method: 'POST', uri: '/v1/web/screenshot-jobs', status: '201' }],
    ['HTTP 503', counterDelta(after.http.queueFull, before.http.queueFull),
      'http_server_requests_seconds_count',
      { method: 'POST', uri: '/v1/web/screenshot-jobs', status: '503' }],
    ['queue delay', counterDelta(after.screenshot.queueDelay, before.screenshot.queueDelay),
      'nexus_screenshot_queue_delay_seconds_count', {}],
    ['async execution success', counterDelta(
      after.screenshot.executionAsyncSucceeded,
      before.screenshot.executionAsyncSucceeded),
    'nexus_screenshot_execution_seconds_count',
    { mode: 'async', outcome: 'succeeded' }],
  ]
  for (const [label, actuatorDelta, metricName, labels] of checks) {
    const prometheusDelta = prometheusValue(afterPrometheus.text, metricName, labels)
      - prometheusValue(beforePrometheus.text, metricName, labels)
    assert.equal(prometheusDelta, actuatorDelta, `${label} differs between metric outlets`)
  }
}

async function osSample() {
  const command = String.raw`
    $ErrorActionPreference='Stop'
    Add-Type -AssemblyName Microsoft.VisualBasic
    $computer=[Microsoft.VisualBasic.Devices.ComputerInfo]::new()
    $java=Get-Process -Id ([int]$env:NEXUS_EXPERIMENT_SERVER_PID) -ErrorAction Stop
    $root=[IO.Path]::GetFullPath($env:NEXUS_EXPERIMENT_BROWSER_ROOT)
    $chrome=@(Get-Process -Name 'chrome-headless-shell' -ErrorAction SilentlyContinue | Where-Object {
      try { $_.Path -and [IO.Path]::GetFullPath($_.Path).StartsWith($root,[StringComparison]::OrdinalIgnoreCase) } catch { $false }
    })
    $javaCpu=if ($null -eq $java.CPU) { 0 } else { $java.CPU }
    $chromeWorkingSet=($chrome | Measure-Object WorkingSet64 -Sum).Sum
    $chromePrivate=($chrome | Measure-Object PrivateMemorySize64 -Sum).Sum
    $chromeCpu=($chrome | Measure-Object CPU -Sum).Sum
    if ($null -eq $chromeWorkingSet) { $chromeWorkingSet=0 }
    if ($null -eq $chromePrivate) { $chromePrivate=0 }
    if ($null -eq $chromeCpu) { $chromeCpu=0 }
    [pscustomobject]@{
      at=[DateTimeOffset]::UtcNow.ToString('O')
      totalPhysicalBytes=[uint64]$computer.TotalPhysicalMemory
      availablePhysicalBytes=[uint64]$computer.AvailablePhysicalMemory
      javaWorkingSetBytes=[uint64]$java.WorkingSet64
      javaPrivateBytes=[uint64]$java.PrivateMemorySize64
      javaCpuSeconds=[double]$javaCpu
      chromeProcessCount=$chrome.Count
      chromeWorkingSetBytes=[uint64]$chromeWorkingSet
      chromePrivateBytes=[uint64]$chromePrivate
      chromeCpuSeconds=[double]$chromeCpu
    } | ConvertTo-Json -Compress
  `
  const { stdout } = await execFileAsync('powershell.exe', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', command,
  ], { timeout: 5_000, windowsHide: true })
  return JSON.parse(stdout.trim())
}

async function observeUntil(control) {
  const samples = []
  let consecutiveHighCpuSamples = 0
  while (!control.stop) {
    const started = performance.now()
    const [unfinished, slotBusy, waiters, states, hikariActive, hikariIdle,
      hikariPending, tomcatBusy, tomcatCurrent, processCpu, systemCpu,
      heapUsed, heapMax, liveThreads, operatingSystem] = await Promise.all([
      metricValue('nexus.screenshot.jobs.unfinished'),
      metricValue('nexus.screenshot.slot.busy'),
      metricValue('nexus.ratelimit.waiters'),
      metricValue('nexus.ratelimit.states'),
      metricValue('hikaricp.connections.active'),
      metricValue('hikaricp.connections.idle'),
      metricValue('hikaricp.connections.pending'),
      metricValue('tomcat.threads.busy'),
      metricValue('tomcat.threads.current'),
      metricValue('process.cpu.usage'),
      metricValue('system.cpu.usage'),
      metricValue('jvm.memory.used', { area: 'heap' }),
      metricValue('jvm.memory.max', { area: 'heap' }),
      metricValue('jvm.threads.live'),
      osSample(),
    ])
    const sample = {
      at: new Date().toISOString(), phase: control.phase ?? 'unspecified',
      unfinished, slotBusy, waiters, states,
      hikariActive, hikariIdle, hikariPending, tomcatBusy, tomcatCurrent,
      processCpu, systemCpu, heapUsed, heapMax, liveThreads, ...operatingSystem,
    }
    samples.push(sample)
    const minimumAvailable = Math.max(
      1024 ** 3, sample.totalPhysicalBytes * 0.15)
    if (sample.availablePhysicalBytes < minimumAvailable) {
      control.safetyFailure = `physical_memory_headroom:${sample.availablePhysicalBytes}`
      control.stop = true
      break
    }
    if (sample.unfinished > 10) {
      control.safetyFailure = `unfinished_capacity:${sample.unfinished}`
      control.stop = true
      break
    }
    if (![0, 1].includes(sample.slotBusy)) {
      control.safetyFailure = `invalid_slot_busy:${sample.slotBusy}`
      control.stop = true
      break
    }
    consecutiveHighCpuSamples = sample.systemCpu >= 0.95
      ? consecutiveHighCpuSamples + 1 : 0
    if (consecutiveHighCpuSamples >= 10) {
      control.safetyFailure = 'system_cpu_above_95_percent_for_10_samples'
      control.stop = true
      break
    }
    await sleep(Math.max(0, 1_000 - (performance.now() - started)))
  }
  return { samples, safetyFailure: control.safetyFailure }
}

function screenshotSubmit(fullApiKey) {
  return new Promise((resolve) => {
    const started = performance.now()
    const body = Buffer.from(JSON.stringify({ url: fixtureUrl }))
    const request = http.request({
      protocol: apiOrigin.protocol,
      hostname: apiOrigin.hostname,
      port: apiOrigin.port,
      path: '/v1/web/screenshot-jobs',
      method: 'POST',
      agent,
      headers: {
        Authorization: `ApiKey ${fullApiKey}`,
        'Content-Type': 'application/json',
        'Content-Length': body.length,
      },
      timeout: requestTimeoutMs,
    }, (response) => {
      const chunks = []
      response.on('data', (chunk) => chunks.push(chunk))
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let parsed
        try { parsed = text ? JSON.parse(text) : null } catch { parsed = null }
        resolve({
          status: response.statusCode,
          code: parsed?.code,
          jobId: parsed?.data?.jobId,
          retryAfter: response.headers['retry-after'],
          durationMs: performance.now() - started,
        })
      })
    })
    request.on('timeout', () => request.destroy(new Error('client_timeout')))
    request.on('error', (error) => resolve({
      status: 0, code: error.message, durationMs: performance.now() - started,
    }))
    request.end(body)
  })
}

async function getJob(fullApiKey, jobId) {
  return apiCall(`/v1/web/screenshot-jobs/${jobId}`, {
    authorization: `ApiKey ${fullApiKey}`,
  })
}

async function awaitTerminal(fullApiKey, jobId, timeoutMs = 45_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const job = await getJob(fullApiKey, jobId)
    if (['SUCCEEDED', 'FAILED', 'EXPIRED'].includes(job.status)) return job
    await sleep(250)
  }
  throw new Error(`Job ${jobId} did not reach a terminal state`)
}

async function downloadResult(fullApiKey, jobId) {
  const response = await fetch(new URL(
    `/v1/web/screenshot-jobs/${jobId}/result`, apiOrigin), {
    headers: { Authorization: `ApiKey ${fullApiKey}` },
    signal: AbortSignal.timeout(requestTimeoutMs),
  })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'image/png')
  assert.equal(response.headers.get('cache-control'), 'no-store')
  const bytes = new Uint8Array(await response.arrayBuffer())
  assert.deepEqual([...bytes.slice(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10])
  return bytes.length
}

async function quota(applicationId, bearer) {
  return apiCall(`/api/application/${applicationId}/quota`, {
    authorization: `Bearer ${bearer}`,
  })
}

async function usageCount(applicationId, apiKeyId, bearer) {
  const query = new URLSearchParams({
    applicationId: String(applicationId), apiKeyId: String(apiKeyId), timeRange: 'TODAY',
  })
  return (await apiCall(`/api/usage?${query}`, {
    authorization: `Bearer ${bearer}`,
  })).allTimeCount
}

async function waitForUsage(expected, resources) {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    if (await usageCount(resources.applicationId, resources.apiKeyId,
      resources.bearer) === expected) return
    await sleep(250)
  }
  throw new Error(`Usage did not converge to ${expected}`)
}

async function waitForColdState() {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const [unfinished, busy, waiters, states] = await Promise.all([
      metricValue('nexus.screenshot.jobs.unfinished'),
      metricValue('nexus.screenshot.slot.busy'),
      metricValue('nexus.ratelimit.waiters'),
      metricValue('nexus.ratelimit.states'),
    ])
    if (unfinished === 0 && busy === 0 && waiters === 0 && states === 0) return
    await sleep(250)
  }
  throw new Error('System did not return to unfinished/busy/waiters/states = 0')
}

async function waitForScreenshotIdle() {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const [unfinished, busy] = await Promise.all([
      metricValue('nexus.screenshot.jobs.unfinished'),
      metricValue('nexus.screenshot.slot.busy'),
    ])
    if (unfinished === 0 && busy === 0) return
    await sleep(250)
  }
  throw new Error('Screenshot subsystem did not return to unfinished/busy = 0')
}

async function setupResources() {
  const managementHealth = await jsonCall(managementOrigin, '/actuator/health')
  assert.equal(managementHealth.body?.status, 'UP')
  const businessHealth = await jsonCall(apiOrigin, '/actuator/health', { expectOk: false })
  assert.equal(businessHealth.status, 404)
  const environmentEndpoint = await jsonCall(managementOrigin, '/actuator/env', { expectOk: false })
  assert.equal(environmentEndpoint.status, 404)

  await jsonCall(apiOrigin, '/v1/utils/uuid', { expectOk: false })
  assert(await metric('http.server.requests'), 'HTTP meter must exist after the 401 probe')
  assert.equal(await metricValue('nexus.screenshot.jobs.unfinished'), 0)
  assert.equal(await metricValue('nexus.screenshot.slot.busy'), 0)
  assert.notEqual(await metric('tomcat.threads.busy'), null,
    'Tomcat MBean metrics must be enabled only for this experiment process')

  await apiCall('/api/auth/register', {
    method: 'POST', body: { email, displayName: `Screenshot experiment ${suffix}`, password },
  })
  const login = await apiCall('/api/auth/login', {
    method: 'POST', body: { email, password },
  })
  const application = await apiCall('/api/application/create', {
    method: 'POST', body: { name: `obs-screenshot-${suffix}` },
    authorization: `Bearer ${login.accessToken}`,
  })
  const key = await apiCall('/api/apiKey/create', {
    method: 'POST', body: {
      applicationId: application.id, name: `obs-screenshot-key-${suffix}`,
    },
    authorization: `Bearer ${login.accessToken}`,
  })
  const resources = {
    email, applicationId: application.id, apiKeyId: key.id,
    bearer: login.accessToken, fullApiKey: key.apiKey,
  }
  await writeFile(path.join(runDirectory, 'resource-manifest.json'), JSON.stringify({
    email, applicationId: application.id, apiKeyId: key.id,
  }, null, 2), 'utf8')
  const initialQuota = await quota(application.id, login.accessToken)
  assert.equal(initialQuota.quotaLimit, 1_000)
  assert.equal(initialQuota.usedCount, 0)
  return { resources, initialQuota }
}

async function runOneAndWait(resources) {
  const submitted = await screenshotSubmit(resources.fullApiKey)
  assert.equal(submitted.status, 201)
  assert.equal(submitted.code, 'SUCCESS')
  assert(submitted.jobId)
  const terminal = await awaitTerminal(resources.fullApiKey, submitted.jobId)
  assert.equal(terminal.status, 'SUCCEEDED',
    `Controlled screenshot failed with ${terminal.failureCode ?? 'unknown code'}`)
  const executionMs = Date.parse(terminal.completedAt) - Date.parse(terminal.startedAt)
  assert(executionMs >= 500 && executionMs <= 20_000,
    `Controlled execution duration is outside the experiment boundary: ${executionMs}ms`)
  return { submitted, terminal, executionMs }
}

async function runPreflight(resources) {
  await waitForColdState()
  const before = await boundarySnapshot()
  const beforePrometheus = await prometheusSnapshot('preflight-before')
  const observerControl = { stop: false, safetyFailure: undefined }
  const observerPromise = observeUntil(observerControl)
  let completed
  let pngBytes
  try {
    completed = await runOneAndWait(resources)
    pngBytes = await downloadResult(resources.fullApiKey, completed.submitted.jobId)
  } finally {
    observerControl.stop = true
  }
  const observer = await observerPromise
  assert.equal(observer.safetyFailure, undefined)
  await waitForUsage(1, resources)
  const finalQuota = await quota(resources.applicationId, resources.bearer)
  assert.equal(finalQuota.usedCount, 1,
    'One accepted preflight job must consume quota exactly once')
  await waitForColdState()
  const after = await boundarySnapshot()
  const afterPrometheus = await prometheusSnapshot('preflight-after')
  assertDualOutletAgreement(before, after, beforePrometheus, afterPrometheus)
  assert.equal(counterDelta(after.http.created, before.http.created), 1)
  assert.equal(counterDelta(after.screenshot.queueDelay, before.screenshot.queueDelay), 1)
  assert.equal(counterDelta(after.screenshot.executionAsyncSucceeded,
    before.screenshot.executionAsyncSucceeded), 1)
  assert.equal(after.screenshot.unfinished, 0)
  assert.equal(after.screenshot.slotBusy, 0)
  assert(Math.max(...observer.samples.map((item) => item.unfinished)) <= 1)
  assert(Math.max(...observer.samples.map((item) => item.slotBusy)) <= 1)
  assert(observer.samples.some((item) => item.chromeProcessCount > 0),
    'OS samples did not observe controlled Chromium')
  return {
    before, after, observer, pngBytes, finalQuota,
    job: completed.terminal,
    executionMs: completed.executionMs,
    prometheus: [beforePrometheus.filename, afterPrometheus.filename],
  }
}

async function runCalibration(resources) {
  await waitForColdState()
  const beforeCold = await boundarySnapshot()
  const beforeColdPrometheus = await prometheusSnapshot('calibration-t0-before-cold')
  const observerControl = {
    stop: false, safetyFailure: undefined, phase: 'cold',
  }
  const observerPromise = observeUntil(observerControl)
  let coldJob
  const warmJobs = []
  let afterCold
  let afterColdPrometheus
  let coldQuota
  let workFailure
  try {
    coldJob = await runOneAndWait(resources)
    if (observerControl.safetyFailure) {
      throw new Error(`Cold diagnostic safety stop: ${observerControl.safetyFailure}`)
    }
    await waitForUsage(1, resources)
    coldQuota = await quota(resources.applicationId, resources.bearer)
    assert.equal(coldQuota.usedCount, 1,
      'One accepted cold diagnostic job must consume quota exactly once')
    await waitForScreenshotIdle()
    afterCold = await boundarySnapshot()
    afterColdPrometheus = await prometheusSnapshot('calibration-t1-after-cold')
    assertDualOutletAgreement(
      beforeCold, afterCold, beforeColdPrometheus, afterColdPrometheus)

    observerControl.phase = 'warm'
    for (let index = 0; index < 3; index += 1) {
      if (observerControl.safetyFailure) {
        throw new Error(`Warm calibration safety stop: ${observerControl.safetyFailure}`)
      }
      warmJobs.push(await runOneAndWait(resources))
    }
  } catch (error) {
    workFailure = error
  } finally {
    observerControl.stop = true
  }
  const observer = await observerPromise
  if (workFailure) throw workFailure
  assert.equal(observer.safetyFailure, undefined)

  await waitForUsage(4, resources)
  const finalQuota = await quota(resources.applicationId, resources.bearer)
  assert.equal(finalQuota.usedCount, 4,
    'One cold and three warm jobs must consume quota exactly four times')
  await waitForColdState()
  const afterWarm = await boundarySnapshot()
  const afterWarmPrometheus = await prometheusSnapshot('calibration-t2-after-warm')
  assertDualOutletAgreement(
    afterCold, afterWarm, afterColdPrometheus, afterWarmPrometheus)

  const coldExecutionCount = counterDelta(
    afterCold.screenshot.executionAsyncSucceeded,
    beforeCold.screenshot.executionAsyncSucceeded)
  const coldGateSeconds = statistic(
    afterCold.screenshot.executionAsyncSucceeded, 'TOTAL_TIME') - statistic(
    beforeCold.screenshot.executionAsyncSucceeded, 'TOTAL_TIME')
  assert.equal(coldExecutionCount, 1,
    'Cold diagnostic must record exactly one successful Gate execution')
  assert(coldGateSeconds > 0,
    'Cold diagnostic Gate execution time must be positive')
  assert.equal(counterDelta(afterCold.screenshot.executionAsyncFailed,
    beforeCold.screenshot.executionAsyncFailed), 0,
  'Cold diagnostic must not record a failed Gate execution')
  assert.equal(counterDelta(afterCold.screenshot.queueDelay,
    beforeCold.screenshot.queueDelay), 1,
  'Cold diagnostic must record exactly one first queue delay')
  assert.equal(counterDelta(afterCold.http.created, beforeCold.http.created), 1,
    'Cold diagnostic must return exactly one HTTP 201 response')

  const warmExecutionCount = counterDelta(
    afterWarm.screenshot.executionAsyncSucceeded,
    afterCold.screenshot.executionAsyncSucceeded)
  const warmGateTotalSeconds = statistic(
    afterWarm.screenshot.executionAsyncSucceeded, 'TOTAL_TIME') - statistic(
    afterCold.screenshot.executionAsyncSucceeded, 'TOTAL_TIME')
  assert.equal(warmExecutionCount, 3,
    'Warm calibration must record exactly three successful Gate executions')
  assert(warmGateTotalSeconds > 0,
    'Warm calibration Gate execution total time must be positive')
  assert.equal(counterDelta(afterWarm.screenshot.executionAsyncFailed,
    afterCold.screenshot.executionAsyncFailed), 0,
  'Warm calibration must not record failed Gate executions')
  assert.equal(counterDelta(afterWarm.screenshot.queueDelay,
    afterCold.screenshot.queueDelay), 3,
  'Warm calibration must record one first queue delay per job')
  assert.equal(counterDelta(afterWarm.http.created, afterCold.http.created), 3,
    'Warm calibration must return exactly three HTTP 201 responses')

  assert.equal(counterDelta(afterWarm.http.queueFull, beforeCold.http.queueFull), 0,
    'Serial calibration must not reach screenshot capacity')
  assert.equal(counterDelta(afterWarm.http.rateLimited, beforeCold.http.rateLimited), 0,
    'Application rate limiting must not affect serial calibration')
  assert.equal(counterDelta(afterWarm.hikari.timeout, beforeCold.hikari.timeout), 0,
    'Hikari timeout counter increased during calibration')
  assert.equal(Math.max(0, ...observer.samples.map(
    (item) => item.hikariPending ?? 0)), 0,
  'Hikari pending connections became a competing bottleneck')
  assert.equal(Math.max(0, ...observer.samples.map(
    (item) => item.unfinished ?? 0)), 1,
  'Strictly serial calibration must have an unfinished peak of exactly one')
  assert.equal(Math.max(0, ...observer.samples.map(
    (item) => item.slotBusy ?? 0)), 1,
  'Strictly serial calibration must observe the unique Gate as busy')
  assert(observer.samples.some((item) => item.phase === 'cold'
    && item.chromeProcessCount > 0),
  'OS samples did not observe Chromium during the cold diagnostic')
  assert(observer.samples.some((item) => item.phase === 'warm'
    && item.chromeProcessCount > 0),
  'OS samples did not observe Chromium during warm calibration')
  const usageFailureDelta = usageFailureReasons.reduce((total, reason) => total
    + counterDelta(afterWarm.usageFailures[reason],
      beforeCold.usageFailures[reason]), 0)
  assert.equal(usageFailureDelta, 0,
    'Usage final recording failures increased during calibration')

  const warmDurations = warmJobs.map((job) => job.executionMs)
  const sortedDurations = [...warmDurations].sort((a, b) => a - b)
  const persistedMeanMs = warmDurations.reduce(
    (total, value) => total + value, 0) / warmDurations.length
  const persistedMedianMs = sortedDurations[1]
  const persistedStandardDeviationMs = Math.sqrt(warmDurations.reduce(
    (total, value) => total + (value - persistedMeanMs) ** 2, 0)
    / warmDurations.length)
  const coefficientOfVariation = persistedStandardDeviationMs / persistedMeanMs
  const maxMinRatio = sortedDurations[2] / sortedDurations[0]
  const warmGateMeanSeconds = warmGateTotalSeconds / warmExecutionCount
  const gateOccupancyRate = warmExecutionCount / warmGateTotalSeconds
  const modeledWorkerCycleSeconds = warmGateMeanSeconds + workerFixedDelaySeconds
  const modeledWorkerDrainRate = 1 / modeledWorkerCycleSeconds
  const proposedArrivalRates = [0.5, 1, 2, 4].map((multiple) => {
    const rateJobsPerSecond = Math.min(5, modeledWorkerDrainRate * multiple)
    return {
      multiple,
      rateJobsPerSecond,
      plannedRequestsFor30Seconds: Math.ceil(rateJobsPerSecond * 30),
    }
  })
  const coldToWarmGateRatio = coldGateSeconds / warmGateMeanSeconds
  const invalidReasons = []
  if (coefficientOfVariation > 0.25) {
    invalidReasons.push(`execution_time_cv:${coefficientOfVariation}`)
  }
  if (maxMinRatio > 1.5) {
    invalidReasons.push(`execution_time_max_min:${maxMinRatio}`)
  }
  return {
    valid: invalidReasons.length === 0,
    invalidReasons,
    cold: {
      executionMs: coldJob.executionMs,
      gateSeconds: coldGateSeconds,
      quota: coldQuota,
    },
    warm: {
      executionMs: warmDurations,
      persistedMeanMs,
      persistedMedianMs,
      persistedStandardDeviationMs,
      coefficientOfVariation,
      maxMinRatio,
      gate: {
        count: warmExecutionCount,
        totalTimeSeconds: warmGateTotalSeconds,
        meanSeconds: warmGateMeanSeconds,
      },
    },
    coldToWarmGateRatio,
    capacityModel: {
      unfinishedCapacity,
      runningAtCapacity: 1,
      maximumQueuedWhenBusy: unfinishedCapacity - 1,
    },
    rateModel: {
      gateOccupancyRateJobsPerSecond: gateOccupancyRate,
      workerFixedDelaySeconds,
      modeledWorkerCycleSeconds,
      modeledWorkerDrainRateJobsPerSecond: modeledWorkerDrainRate,
      arrivalRateBasis: 'modeledWorkerDrainRateJobsPerSecond',
      proposedArrivalRates,
    },
    observer,
    beforeCold,
    afterCold,
    afterWarm,
    finalQuota,
    prometheus: [
      beforeColdPrometheus.filename,
      afterColdPrometheus.filename,
      afterWarmPrometheus.filename,
    ],
  }
}

function percentile(values, fraction) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1,
    Math.floor((sorted.length - 1) * fraction))]
}

async function runLoad(resources) {
  await waitForColdState()
  const before = await boundarySnapshot()
  const beforePrometheus = await prometheusSnapshot('load-before')
  const intervalMs = 1_000 / loadRate
  const measurementWindowMs = durationSeconds * 1_000
  const dueTimesMs = []
  for (let index = 0; ; index += 1) {
    const due = index * intervalMs
    if (due >= measurementWindowMs) break
    dueTimesMs.push(due)
  }
  const planned = dueTimesMs.length
  assert(planned > 0, 'Open-loop schedule must contain at least one request')
  const startedAt = performance.now()
  const results = []
  let inFlight = 0
  let maxInFlightObserved = 0
  let completed = 0
  let abortReason
  const observerControl = { stop: false, safetyFailure: undefined }
  const observerPromise = observeUntil(observerControl)
  const midpointPromise = (async () => {
    await sleep(durationSeconds * 500)
    return prometheusSnapshot('load-mid')
  })()

  await new Promise((resolve) => {
    for (const due of dueTimesMs) {
      setTimeout(async () => {
        const latenessMs = performance.now() - startedAt - due
        if (!abortReason && observerControl.safetyFailure) {
          abortReason = observerControl.safetyFailure
        }
        if (!abortReason && latenessMs > intervalMs) {
          abortReason = `scheduler_late:${latenessMs.toFixed(1)}ms`
        }
        if (!abortReason && inFlight >= maxInFlight) {
          abortReason = `in_flight_limit:${inFlight}`
        }
        if (abortReason) {
          results.push({ status: -1, code: 'NOT_SENT', durationMs: 0,
            latenessMs, terminalChecked: true })
        } else {
          inFlight += 1
          maxInFlightObserved = Math.max(maxInFlightObserved, inFlight)
          const submitted = await screenshotSubmit(resources.fullApiKey)
          inFlight -= 1
          results.push({ ...submitted, latenessMs,
            terminalChecked: submitted.status !== 201 })
        }
        completed += 1
        if (completed === planned) resolve()
      }, due)
    }
  })
  let drainFailure
  try {
    const drainDeadline = Date.now() + 60_000
    while (Date.now() < drainDeadline) {
      if (await metricValue('nexus.screenshot.jobs.unfinished') === 0) break
      await sleep(500)
    }
    assert.equal(await metricValue('nexus.screenshot.jobs.unfinished'), 0,
      'Accepted screenshot jobs did not drain within 60 seconds')
    for (const result of results.filter((item) => item.status === 201)) {
      const terminal = await getJob(resources.fullApiKey, result.jobId)
      result.terminalChecked = true
      result.terminalStatus = terminal.status
      result.executionMs = Date.parse(terminal.completedAt) - Date.parse(terminal.startedAt)
    }
  } catch (error) {
    drainFailure = error
  } finally {
    observerControl.stop = true
  }
  const observer = await observerPromise
  if (drainFailure) throw drainFailure
  const midpointPrometheus = await midpointPromise
  if (observer.safetyFailure) abortReason ??= observer.safetyFailure

  const accepted = results.filter((item) => item.status === 201)
  const queueFull = results.filter((item) => item.status === 503
    && item.code === 'SCREENSHOT_JOB_QUEUE_FULL')
  const unexpected = results.filter((item) => !(
    item.status === 201
    || (item.status === 503 && item.code === 'SCREENSHOT_JOB_QUEUE_FULL')))
  await waitForUsage(accepted.length + queueFull.length, resources)
  await waitForColdState()
  const after = await boundarySnapshot()
  const afterPrometheus = await prometheusSnapshot('load-after-drain')
  assertDualOutletAgreement(before, after, beforePrometheus, afterPrometheus)

  const finalQuota = await quota(resources.applicationId, resources.bearer)
  assert.equal(finalQuota.usedCount, accepted.length,
    'Quota must equal accepted jobs, not all attempts')
  assert.equal(await usageCount(resources.applicationId, resources.apiKeyId,
    resources.bearer), accepted.length + queueFull.length)
  assert.equal(unexpected.length, 0, 'Load returned an unexpected response')
  assert(accepted.every((item) => item.terminalStatus === 'SUCCEEDED'),
    'Every accepted controlled job must succeed')
  assert(queueFull.every((item) => item.retryAfter === '5'),
    'Every queue-full response must include Retry-After: 5')
  assert.equal(counterDelta(after.http.rateLimited, before.http.rateLimited), 0,
    'Application rate limiting became a competing treatment')
  assert.equal(counterDelta(after.http.created, before.http.created), accepted.length,
    'HTTP 201 metric does not match accepted submissions')
  assert.equal(counterDelta(after.http.queueFull, before.http.queueFull), queueFull.length,
    'HTTP 503 metric does not match queue-full submissions')
  assert.equal(counterDelta(after.screenshot.queueDelay,
    before.screenshot.queueDelay), accepted.length,
  'Every accepted job must record one first queue delay')
  assert.equal(counterDelta(after.screenshot.executionAsyncSucceeded,
    before.screenshot.executionAsyncSucceeded), accepted.length,
  'Every accepted job must record one successful async execution')
  assert.equal(counterDelta(after.screenshot.executionAsyncFailed,
    before.screenshot.executionAsyncFailed), 0,
  'Controlled workload must not record failed executions')
  assert(statistic(after.screenshot.executionAsyncSucceeded, 'MAX') <= 10,
    'A controlled execution held the Gate for more than 10 seconds')
  assert.equal(counterDelta(after.hikari.timeout, before.hikari.timeout), 0,
    'Hikari timeout counter increased')
  assert.equal(Math.max(0, ...observer.samples.map(
    (item) => item.hikariPending ?? 0)), 0,
  'Hikari pending connections became a competing bottleneck')
  const usageFailureDelta = usageFailureReasons.reduce((total, reason) => total
    + counterDelta(after.usageFailures[reason], before.usageFailures[reason]), 0)
  assert.equal(usageFailureDelta, 0, 'Usage final recording failures increased')
  const peakUnfinished = Math.max(0,
    ...observer.samples.map((item) => item.unfinished))
  if (backpressureExpectation === 'required') {
    assert.equal(peakUnfinished, 10,
      'Backpressure treatment must reach the global unfinished capacity')
    assert(queueFull.length > 0, 'Treatment did not produce queue-full responses')
  } else if (backpressureExpectation === 'forbidden') {
    assert(peakUnfinished < 10,
      'Backpressure-forbidden control reached the global unfinished capacity')
    assert.equal(queueFull.length, 0,
      'Backpressure-forbidden control produced queue-full responses')
  }
  const durations = results.filter((item) => item.status > 0)
    .map((item) => item.durationMs)
  return {
    configuration: {
      loadRate,
      durationSeconds,
      planned,
      intervalMs,
      firstPlannedDueMs: dueTimesMs[0],
      lastPlannedDueMs: dueTimesMs.at(-1),
      finiteWindowCountRate: planned / durationSeconds,
      backpressureExpectation,
    },
    counts: Object.fromEntries([...new Set(results.map(
      (item) => `${item.status}:${item.code ?? ''}`))].map((key) => [
      key, results.filter((item) => `${item.status}:${item.code ?? ''}` === key).length,
    ])),
    accepted: accepted.length,
    queueFull: queueFull.length,
    abortReason,
    maxInFlightObserved,
    maxSchedulingLatenessMs: Math.max(...results.map((item) => item.latenessMs)),
    latencyMs: {
      p50: percentile(durations, 0.50), p95: percentile(durations, 0.95),
      p99: percentile(durations, 0.99),
      max: durations.length ? Math.max(...durations) : null,
    },
    observer,
    before,
    after,
    finalQuota,
    prometheus: [beforePrometheus.filename, midpointPrometheus.filename,
      afterPrometheus.filename],
  }
}

async function softDelete(resources) {
  if (!resources?.applicationId || !resources?.bearer) return false
  await apiCall(`/api/application/${resources.applicationId}`, {
    method: 'DELETE', authorization: `Bearer ${resources.bearer}`,
  })
  return true
}

const result = {
  runId, mode, startedAt: new Date().toISOString(),
  configuration: {
    apiOrigin: apiOrigin.origin,
    managementOrigin: managementOrigin.origin,
    fixtureUrl,
    requestTimeoutMs,
    maxInFlight,
    ...(mode === 'load' && { loadRate, durationSeconds, backpressureExpectation }),
  },
}
let resources
let failure
try {
  const setup = await setupResources()
  resources = setup.resources
  result.initialQuota = setup.initialQuota
  if (mode === 'preflight') result.preflight = await runPreflight(resources)
  else if (mode === 'calibrate') {
    result.calibration = await runCalibration(resources)
    if (!result.calibration.valid) {
      throw new Error(`Calibration invalid: ${result.calibration.invalidReasons.join(',')}`)
    }
  }
  else result.load = await runLoad(resources)
  result.completedAt = new Date().toISOString()
} catch (error) {
  failure = error
  result.failedAt = new Date().toISOString()
  result.failure = { name: error.name, message: error.message, stack: error.stack }
} finally {
  try { result.softDeleted = await softDelete(resources) } catch (error) {
    result.softDeleteFailure = error.message
    failure ??= error
  }
  agent.destroy()
  result.resources = resources ? {
    email, applicationId: resources.applicationId, apiKeyId: resources.apiKeyId,
  } : { email }
  await writeFile(path.join(runDirectory, 'result.json'),
    JSON.stringify(result, null, 2), 'utf8')
  console.log(`RESULT_FILE=${path.join(runDirectory, 'result.json')}`)
  console.log(`RESOURCE_EMAIL=${email}`)
}
if (failure) throw failure
