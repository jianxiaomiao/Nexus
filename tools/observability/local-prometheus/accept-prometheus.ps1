[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('BeforeRestart', 'AfterRestart')]
    [string]$Phase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (
    Resolve-Path (
        Join-Path $PSScriptRoot '..\..\..'
    )
).Path

$runtimeRoot = Join-Path `
    $repositoryRoot `
    'data\observability\prometheus'

$tsdbDirectory = Join-Path $runtimeRoot 'tsdb'
$checkpointPath = Join-Path `
    $runtimeRoot `
    'acceptance-checkpoint.json'

$nexusBaseUri = 'http://127.0.0.1:8081'
$prometheusBaseUri = 'http://127.0.0.1:9090'
$jobName = 'nexus-local'
$sampleWaitSeconds = 35
$minimumSamplesInOneMinute = 2
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Message)

    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Get-HttpStatusCode {
    param([Parameter(Mandatory = $true)][string]$Uri)

    try {
        $response = Invoke-WebRequest `
            -Uri $Uri `
            -Method Get `
            -TimeoutSec 5 `
            -UseBasicParsing

        return [int]$response.StatusCode
    }
    catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }

        throw
    }
}

function Assert-HttpSuccess {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $statusCode = Get-HttpStatusCode -Uri $Uri

    if ($statusCode -lt 200 -or $statusCode -ge 300) {
        throw "$Description returned HTTP $statusCode instead of 2xx: $Uri"
    }

    Write-Pass "$Description returned HTTP $statusCode."
}

function Assert-HttpNotExposed {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $statusCode = Get-HttpStatusCode -Uri $Uri

    if ($statusCode -ge 200 -and $statusCode -lt 300) {
        throw "$Description is unexpectedly exposed with HTTP ${statusCode}: $Uri"
    }

    Write-Pass "$Description is not exposed (HTTP $statusCode)."
}

function Get-LoopbackListenerProcessId {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $netstatLines = @(& netstat.exe -ano -p tcp)

    if ($LASTEXITCODE -ne 0) {
        throw "netstat failed with exit code $LASTEXITCODE."
    }

    $listenerPattern = (
        '^\s*TCP\s+(?<address>\S+):' +
        [regex]::Escape([string]$Port) +
        '\s+\S+\s+LISTENING\s+(?<processId>\d+)\s*$'
    )

    $listeners = @(
        foreach ($line in $netstatLines) {
            if ($line -match $listenerPattern) {
                [pscustomobject]@{
                    LocalAddress = $Matches.address.Trim('[', ']')
                    OwningProcess = [int]$Matches.processId
                }
            }
        }
    )

    if ($listeners.Count -eq 0) {
        throw "$Description is not listening on port $Port."
    }

    $nonLoopbackAddresses = @(
        $listeners |
            Where-Object {
                $_.LocalAddress -notin @('127.0.0.1', '::1')
            } |
            Select-Object -ExpandProperty LocalAddress -Unique
    )

    if ($nonLoopbackAddresses.Count -gt 0) {
        throw (
            "$Description has a non-loopback listener on port ${Port}: " +
            ($nonLoopbackAddresses -join ', ')
        )
    }

    $processIds = @(
        $listeners |
            Select-Object -ExpandProperty OwningProcess -Unique
    )

    if ($processIds.Count -ne 1) {
        throw (
            "$Description port $Port has an unexpected number of owning " +
            "processes: $($processIds -join ', ')"
        )
    }

    Write-Pass (
        "$Description listens only on loopback port $Port " +
        "(PID $($processIds[0]))."
    )

    return [int]$processIds[0]
}

function Invoke-PrometheusQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Nullable[double]]$Time
    )

    $encodedQuery = [Uri]::EscapeDataString($Query)
    $uri = "$prometheusBaseUri/api/v1/query?query=$encodedQuery"

    if ($null -ne $Time) {
        $encodedTime = ([double]$Time).ToString('R', $invariantCulture)
        $uri = "$uri&time=$encodedTime"
    }

    $response = Invoke-RestMethod `
        -Uri $uri `
        -Method Get `
        -TimeoutSec 10

    if ($response.status -ne 'success') {
        throw "Prometheus query failed: $Query"
    }

    return @($response.data.result)
}

function ConvertTo-FiniteDouble {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $parsed = 0.0
    $parsedSuccessfully = [double]::TryParse(
        [string]$Value,
        [System.Globalization.NumberStyles]::Float,
        $invariantCulture,
        [ref]$parsed
    )

    if (
        -not $parsedSuccessfully -or
        [double]::IsNaN($parsed) -or
        [double]::IsInfinity($parsed)
    ) {
        throw "$Description is not a finite number: $Value"
    }

    return $parsed
}

function Get-SingleSample {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Description,
        [Nullable[double]]$Time
    )

    $results = @(
        Invoke-PrometheusQuery `
            -Query $Query `
            -Time $Time
    )

    if ($results.Count -ne 1) {
        throw (
            "$Description expected exactly one time series, " +
            "but found $($results.Count). Query: $Query"
        )
    }

    $sample = @($results[0].value)

    if ($sample.Count -ne 2) {
        throw "$Description returned an invalid Prometheus sample."
    }

    return [pscustomobject]@{
        Timestamp = ConvertTo-FiniteDouble `
            -Value $sample[0] `
            -Description "$Description timestamp"
        Value = ConvertTo-FiniteDouble `
            -Value $sample[1] `
            -Description "$Description value"
    }
}

function Assert-TargetUp {
    $response = Invoke-RestMethod `
        -Uri "$prometheusBaseUri/api/v1/targets?state=active" `
        -Method Get `
        -TimeoutSec 10

    if ($response.status -ne 'success') {
        throw 'Prometheus targets API did not return success.'
    }

    $matchingTargets = @(
        $response.data.activeTargets |
            Where-Object {
                $_.labels.job -eq $jobName -and
                $_.scrapeUrl -eq "$nexusBaseUri/actuator/prometheus"
            }
    )

    if ($matchingTargets.Count -ne 1) {
        throw (
            "Expected exactly one $jobName target for $nexusBaseUri, " +
            "but found $($matchingTargets.Count)."
        )
    }

    $target = $matchingTargets[0]

    if ($target.health -ne 'up') {
        throw (
            "Prometheus target is not up. " +
            "health=$($target.health), lastError=$($target.lastError)"
        )
    }

    Write-Pass "$jobName target is UP with no scrape error."
}

function Assert-CurrentMetrics {
    $upSample = Get-SingleSample `
        -Query "up{job=`"$jobName`"}" `
        -Description 'Prometheus scrape status'

    if ($upSample.Value -ne 1.0) {
        throw "up{job=`"$jobName`"} is $($upSample.Value), expected 1."
    }

    Write-Pass "up{job=`"$jobName`"} equals 1."

    foreach ($metricName in @(
        'process_uptime_seconds',
        'nexus_ratelimit_states',
        'hikaricp_connections_active'
    )) {
        $null = Get-SingleSample `
            -Query "$metricName{job=`"$jobName`"}" `
            -Description $metricName

        Write-Pass "$metricName exists and has a finite value."
    }
}

function Assert-CommonBoundaries {
    Assert-HttpSuccess `
        -Uri "$nexusBaseUri/actuator/health" `
        -Description 'Nexus management health'

    Assert-HttpSuccess `
        -Uri "$nexusBaseUri/actuator/prometheus" `
        -Description 'Nexus Prometheus endpoint'

    Assert-HttpSuccess `
        -Uri "$prometheusBaseUri/-/ready" `
        -Description 'Prometheus readiness endpoint'

    Assert-HttpNotExposed `
        -Uri "$nexusBaseUri/actuator/env" `
        -Description 'Nexus environment actuator endpoint'

    Assert-HttpNotExposed `
        -Uri 'http://127.0.0.1:8080/actuator/health' `
        -Description 'Nexus business-port actuator endpoint'

    $nexusProcessId = Get-LoopbackListenerProcessId `
        -Port 8081 `
        -Description 'Nexus management server'

    $prometheusProcessId = Get-LoopbackListenerProcessId `
        -Port 9090 `
        -Description 'Prometheus server'

    Assert-TargetUp
    Assert-CurrentMetrics

    return [pscustomobject]@{
        NexusProcessId = $nexusProcessId
        PrometheusProcessId = $prometheusProcessId
    }
}

function Assert-TsdbHasData {
    if (-not (Test-Path -LiteralPath $tsdbDirectory -PathType Container)) {
        throw "Prometheus TSDB directory does not exist: $tsdbDirectory"
    }

    $tsdbFiles = @(
        Get-ChildItem `
            -LiteralPath $tsdbDirectory `
            -File `
            -Recurse `
            -ErrorAction Stop
    )

    if ($tsdbFiles.Count -eq 0) {
        throw "Prometheus TSDB directory contains no files: $tsdbDirectory"
    }

    $totalBytes = (
        $tsdbFiles |
            Measure-Object -Property Length -Sum
    ).Sum

    if ($null -eq $totalBytes -or $totalBytes -le 0) {
        throw "Prometheus TSDB files contain no data: $tsdbDirectory"
    }

    Write-Pass (
        "TSDB contains $($tsdbFiles.Count) files and $totalBytes bytes."
    )
}

function Invoke-BeforeRestartAcceptance {
    $boundaries = Assert-CommonBoundaries
    Assert-TsdbHasData

    $timestampQuery = `
        "timestamp(process_uptime_seconds{job=`"$jobName`"})"

    $initialSample = Get-SingleSample `
        -Query $timestampQuery `
        -Description 'Initial Nexus uptime sample timestamp'

    Write-Host (
        "Waiting $sampleWaitSeconds seconds for at least two new " +
        'scrape opportunities...'
    ) -ForegroundColor Cyan

    Start-Sleep -Seconds $sampleWaitSeconds

    Assert-TargetUp

    $latestSample = Get-SingleSample `
        -Query $timestampQuery `
        -Description 'Latest Nexus uptime sample timestamp'

    if ($latestSample.Value -le $initialSample.Value) {
        throw (
            'The latest sample timestamp did not advance. ' +
            "before=$($initialSample.Value), after=$($latestSample.Value)"
        )
    }

    Write-Pass (
        'The raw sample timestamp advanced from ' +
        "$($initialSample.Value) to $($latestSample.Value)."
    )

    $sampleCount = Get-SingleSample `
        -Query (
            "count_over_time(" +
            "process_uptime_seconds{job=`"$jobName`"}[1m])"
        ) `
        -Description 'One-minute raw sample count'

    if ($sampleCount.Value -lt $minimumSamplesInOneMinute) {
        throw (
            'The one-minute window contains too few raw samples. ' +
            "actual=$($sampleCount.Value), " +
            "minimum=$minimumSamplesInOneMinute"
        )
    }

    Write-Pass (
        "The one-minute window contains $($sampleCount.Value) raw samples."
    )

    $uptimeSample = Get-SingleSample `
        -Query "process_uptime_seconds{job=`"$jobName`"}" `
        -Description 'Nexus uptime checkpoint sample'

    $processStartSample = Get-SingleSample `
        -Query "process_start_time_seconds{job=`"$jobName`"}" `
        -Description 'Nexus process start time'

    New-Item `
        -ItemType Directory `
        -Force `
        -Path $runtimeRoot | Out-Null

    $checkpoint = [ordered]@{
        schemaVersion = 1
        createdAt = [DateTimeOffset]::Now.ToString('o')
        jobName = $jobName
        prometheusProcessId = $boundaries.PrometheusProcessId
        nexusProcessId = $boundaries.NexusProcessId
        nexusProcessStartTimeSeconds = $processStartSample.Value
        sampleMetric = 'process_uptime_seconds'
        sampleTimestamp = $uptimeSample.Timestamp
        sampleValue = $uptimeSample.Value
    }

    $checkpoint |
        ConvertTo-Json -Depth 4 |
        Set-Content `
            -LiteralPath $checkpointPath `
            -Encoding UTF8

    Write-Pass "Restart checkpoint written to $checkpointPath"
    Write-Host ''
    Write-Host 'BeforeRestart acceptance passed.' -ForegroundColor Green
    Write-Host 'Next: stop only Prometheus with Ctrl+C, restart it, then run:'
    Write-Host (
        '.\tools\observability\local-prometheus\' +
        'accept-prometheus.ps1 -Phase AfterRestart'
    ) -ForegroundColor Cyan
}

function Invoke-AfterRestartAcceptance {
    if (-not (Test-Path -LiteralPath $checkpointPath -PathType Leaf)) {
        throw (
            'Restart checkpoint does not exist. Run BeforeRestart first: ' +
            $checkpointPath
        )
    }

    $checkpoint = Get-Content `
        -LiteralPath $checkpointPath `
        -Raw |
        ConvertFrom-Json

    if ($checkpoint.schemaVersion -ne 1) {
        throw "Unsupported checkpoint schema: $($checkpoint.schemaVersion)"
    }

    if ($checkpoint.jobName -ne $jobName) {
        throw (
            "Checkpoint job is $($checkpoint.jobName), " +
            "but this script expects $jobName."
        )
    }

    $boundaries = Assert-CommonBoundaries
    Assert-TsdbHasData

    if (
        $boundaries.PrometheusProcessId -eq
        [int]$checkpoint.prometheusProcessId
    ) {
        throw (
            'Prometheus PID has not changed. Stop and restart only Prometheus ' +
            'before running AfterRestart.'
        )
    }

    Write-Pass (
        'Prometheus PID changed from ' +
        "$($checkpoint.prometheusProcessId) to " +
        "$($boundaries.PrometheusProcessId)."
    )

    $currentProcessStartSample = Get-SingleSample `
        -Query "process_start_time_seconds{job=`"$jobName`"}" `
        -Description 'Current Nexus process start time'

    if (
        [math]::Abs(
            $currentProcessStartSample.Value -
            [double]$checkpoint.nexusProcessStartTimeSeconds
        ) -gt 0.001
    ) {
        throw (
            'Nexus was restarted between phases, so Prometheus restart is no ' +
            'longer the only changed variable. Run BeforeRestart again.'
        )
    }

    Write-Pass 'Nexus process start time is unchanged between phases.'

    $historicalSample = Get-SingleSample `
        -Query (
            "$($checkpoint.sampleMetric){job=`"$jobName`"}"
        ) `
        -Description 'Pre-restart historical sample' `
        -Time ([double]$checkpoint.sampleTimestamp)

    if (
        [math]::Abs(
            $historicalSample.Timestamp -
            [double]$checkpoint.sampleTimestamp
        ) -gt 0.001
    ) {
        throw (
            'The historical query did not return the exact pre-restart ' +
            'sample timestamp.'
        )
    }

    if (
        [math]::Abs(
            $historicalSample.Value -
            [double]$checkpoint.sampleValue
        ) -gt 0.001
    ) {
        throw (
            'The historical query returned a different pre-restart ' +
            'sample value.'
        )
    }

    Write-Pass (
        'The exact pre-restart sample remains queryable at timestamp ' +
        "$($checkpoint.sampleTimestamp)."
    )

    $latestTimestampSample = Get-SingleSample `
        -Query (
            "timestamp($($checkpoint.sampleMetric)" +
            "{job=`"$jobName`"})"
        ) `
        -Description 'Latest post-restart sample timestamp'

    if (
        $latestTimestampSample.Value -le
        [double]$checkpoint.sampleTimestamp
    ) {
        throw (
            'No sample newer than the pre-restart checkpoint is queryable. ' +
            'Wait for another scrape and retry AfterRestart.'
        )
    }

    Write-Pass (
        'A newer post-restart sample is queryable at timestamp ' +
        "$($latestTimestampSample.Value)."
    )

    Write-Host ''
    Write-Host 'AfterRestart acceptance passed.' -ForegroundColor Green
    Write-Host (
        'Prometheus continuous collection, loopback boundaries, and ' +
        'cross-restart TSDB persistence are verified.'
    )
}

switch ($Phase) {
    'BeforeRestart' {
        Invoke-BeforeRestartAcceptance
    }
    'AfterRestart' {
        Invoke-AfterRestartAcceptance
    }
}
