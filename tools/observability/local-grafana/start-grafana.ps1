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
    'data\observability\grafana'

$applicationDirectory = Join-Path $runtimeRoot 'app'
$stateDirectory = Join-Path $runtimeRoot 'state'
$logDirectory = Join-Path $runtimeRoot 'logs'
$pluginDirectory = Join-Path $runtimeRoot 'plugins'

$grafanaExecutable = Join-Path `
    $applicationDirectory `
    'bin\grafana.exe'

$defaultsPath = Join-Path `
    $applicationDirectory `
    'conf\defaults.ini'

$publicDirectory = Join-Path $applicationDirectory 'public'
$configPath = Join-Path $PSScriptRoot 'custom.ini'

foreach ($requiredPath in @(
    $grafanaExecutable,
    $defaultsPath,
    $publicDirectory,
    $configPath
)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required Grafana path does not exist: $requiredPath"
    }
}

$existingListenerPattern = `
    '^\s*TCP\s+\S+:3000\s+\S+\s+LISTENING\s+\d+\s*$'

$existingListeners = @(
    & netstat.exe -ano -p tcp |
        Where-Object { $_ -match $existingListenerPattern }
)

if ($LASTEXITCODE -ne 0) {
    throw "netstat failed with exit code $LASTEXITCODE."
}

if ($existingListeners.Count -gt 0) {
    throw (
        'Port 3000 is already listening. Stop the existing process or ' +
        'identify it before starting Grafana.'
    )
}

foreach ($directory in @(
    $stateDirectory,
    $logDirectory,
    $pluginDirectory
)) {
    New-Item `
        -ItemType Directory `
        -Force `
        -Path $directory | Out-Null
}

$env:NEXUS_GRAFANA_STATE = $stateDirectory.Replace('\', '/')
$env:NEXUS_GRAFANA_LOGS = $logDirectory.Replace('\', '/')
$env:NEXUS_GRAFANA_PLUGINS = $pluginDirectory.Replace('\', '/')

$grafanaArguments = @(
    'server'
    "--homepath=$applicationDirectory"
    "--config=$configPath"
)

Write-Host 'Starting Grafana with:' -ForegroundColor Cyan
Write-Host "  URL:     http://127.0.0.1:3000"
Write-Host "  Config:  $configPath"
Write-Host "  State:   $stateDirectory"
Write-Host "  Logs:    $logDirectory"
Write-Host "  Plugins: $pluginDirectory"
Write-Host 'Use Ctrl+C in this terminal to stop Grafana.' -ForegroundColor Cyan
Write-Host ''

Push-Location $applicationDirectory

try {
    & $grafanaExecutable @grafanaArguments
    $grafanaExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($grafanaExitCode -ne 0) {
    throw "Grafana exited with code $grafanaExitCode."
}
