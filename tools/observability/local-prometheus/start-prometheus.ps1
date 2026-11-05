[CmdletBinding()]
param()

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

$binaryDirectory = Join-Path $runtimeRoot 'bin'
$tsdbDirectory = Join-Path $runtimeRoot 'tsdb'

$prometheusExecutable = Join-Path `
    $binaryDirectory `
    'prometheus.exe'

$promtoolExecutable = Join-Path `
    $binaryDirectory `
    'promtool.exe'

$configPath = Join-Path `
    $PSScriptRoot `
    'prometheus.yml'

foreach ($requiredFile in @(
    $prometheusExecutable,
    $promtoolExecutable,
    $configPath
)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file does not exist: $requiredFile"
    }
}

$existingListener = Get-NetTCPConnection `
    -State Listen `
    -LocalPort 9090 `
    -ErrorAction SilentlyContinue

if ($null -ne $existingListener) {
    $owners = @(
        $existingListener |
            Select-Object -ExpandProperty OwningProcess -Unique
    ) -join ','

    throw "Port 9090 is already listening. OwningProcess=$owners"
}

$healthResponse = Invoke-RestMethod `
    -Uri 'http://127.0.0.1:8081/actuator/health' `
    -Method Get `
    -TimeoutSec 5

if ($healthResponse.status -ne 'UP') {
    throw "Nexus health is not UP: $($healthResponse.status)"
}

$prometheusText = Invoke-RestMethod `
    -Uri 'http://127.0.0.1:8081/actuator/prometheus' `
    -Method Get `
    -TimeoutSec 5

if ($prometheusText -notmatch 'process_uptime_seconds') {
    throw 'Nexus Prometheus endpoint does not contain process_uptime_seconds.'
}

& $promtoolExecutable check config $configPath

if ($LASTEXITCODE -ne 0) {
    throw "promtool check config failed with exit code $LASTEXITCODE."
}

New-Item `
    -ItemType Directory `
    -Force `
    -Path $tsdbDirectory | Out-Null

$prometheusArguments = @(
    "--config.file=$configPath"
    '--web.listen-address=127.0.0.1:9090'
    "--storage.tsdb.path=$tsdbDirectory"
    '--storage.tsdb.retention.time=7d'
    '--storage.tsdb.retention.size=1GB'
    '--log.level=info'
)

& $prometheusExecutable @prometheusArguments

$prometheusExitCode = $LASTEXITCODE

if ($prometheusExitCode -ne 0) {
    throw "Prometheus exited with code $prometheusExitCode."
}