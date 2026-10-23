$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'run.ps1'
$runtimeRoot = Join-Path $PSScriptRoot 'runtime'
$logPath = Join-Path $runtimeRoot 'elevated-run.log'

try {
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    if (Test-Path -LiteralPath $logPath) {
        Remove-Item -LiteralPath $logPath -Force
    }
    & $scriptPath *>&1 | Tee-Object -FilePath $logPath
    exit 0
} catch {
    $_ | Out-String | Tee-Object -FilePath $logPath -Append | Write-Output
    exit 1
}
