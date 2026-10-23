$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'run.ps1'
$logPath = Join-Path $PSScriptRoot 'runtime\elevated-run.log'

try {
    if (Test-Path -LiteralPath $logPath) {
        Remove-Item -LiteralPath $logPath -Force
    }
    & $scriptPath *>&1 | Tee-Object -FilePath $logPath
    exit 0
} catch {
    $_ | Out-String | Tee-Object -FilePath $logPath -Append | Write-Output
    exit 1
}
