param(
    [string]$DeploymentRoot = 'C:\NexusDeploy'
)

$ErrorActionPreference = 'Stop'
$deploymentRoot = [IO.Path]::GetFullPath($DeploymentRoot)
$acceptPath = Join-Path $deploymentRoot `
    'staging\windows-nginx-service\accept.ps1'
$evidenceRoot = Join-Path $deploymentRoot `
    'evidence\windows-nginx-service'
$logPath = Join-Path $evidenceRoot 'scheduled-accept.log'
$statusPath = Join-Path $evidenceRoot 'scheduled-accept-status.json'

New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null

$startedAt = [DateTimeOffset]::Now
$exitCode = 1
$errorMessage = $null
try {
    "STARTED: $($startedAt.ToString('o')) identity=$([Security.Principal.WindowsIdentity]::GetCurrent().Name)" |
        Set-Content -LiteralPath $logPath -Encoding utf8
    & $acceptPath *>&1 | Tee-Object -LiteralPath $logPath -Append
    $exitCode = 0
} catch {
    $errorMessage = $_.Exception.Message
    $_ | Out-String | Tee-Object -LiteralPath $logPath -Append | Write-Output
} finally {
    $status = [ordered]@{
        startedAt = $startedAt.ToString('o')
        completedAt = [DateTimeOffset]::Now.ToString('o')
        identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        exitCode = $exitCode
        error = $errorMessage
    }
    $status | ConvertTo-Json | Set-Content -LiteralPath $statusPath `
        -Encoding utf8
}

exit $exitCode
