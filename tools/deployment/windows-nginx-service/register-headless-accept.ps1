param(
    [string]$TaskName = 'NexusNginxAcceptance20261021',
    [string]$DeploymentRoot = 'C:\NexusDeploy',
    [ValidateRange(1, 10)]
    [int]$DelayMinutes = 2
)

$ErrorActionPreference = 'Stop'
$expectedAcceptHash = 'A89285B52E8BF1204A05E8700C3494C8F3A20955936B0517A83115B458825475'
$deploymentRoot = [IO.Path]::GetFullPath($DeploymentRoot)
$stagingRoot = Join-Path $deploymentRoot 'staging\windows-nginx-service'
$acceptPath = Join-Path $stagingRoot 'accept.ps1'
$runnerPath = Join-Path $stagingRoot 'run-accept-scheduled.ps1'
$powerShellPath = 'C:\Program Files\PowerShell\7\pwsh.exe'
$serviceName = 'NexusNginx'
$evidenceRoot = Join-Path $deploymentRoot 'evidence\windows-nginx-service'

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Task registration must run in an elevated Administrator PowerShell 7 session.'
    }
}

Assert-Administrator
foreach ($requiredFile in @($acceptPath, $runnerPath, $powerShellPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}
if ((Get-FileHash -LiteralPath $acceptPath -Algorithm SHA256).Hash -ne
    $expectedAcceptHash) {
    throw 'The staged acceptance script does not have the expected corrected hash.'
}
if ((Get-Service -Name $serviceName -ErrorAction Stop).Status -ne 'Stopped') {
    throw "Service $serviceName must be stopped before scheduling acceptance."
}
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    throw "Scheduled task already exists; refusing to overwrite it: $TaskName"
}
if (Get-NetTCPConnection -State Listen -LocalPort 18080 `
        -ErrorAction SilentlyContinue) {
    throw 'Port 18080 already has a listener; acceptance was not scheduled.'
}

New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
$scheduledAt = (Get-Date).AddMinutes($DelayMinutes)
$arguments = '-NoLogo -NoProfile -NonInteractive -File "{0}"' -f $runnerPath
$action = New-ScheduledTaskAction -Execute $powerShellPath `
    -Argument $arguments -WorkingDirectory $stagingRoot
$trigger = New-ScheduledTaskTrigger -Once -At $scheduledAt
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' `
    -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10) `
    -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $TaskName -Action $action `
    -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'One-time headless acceptance for the isolated Nexus Nginx Windows service.' |
    Out-Null

$result = [ordered]@{
    taskName = $TaskName
    scheduledAt = $scheduledAt.ToString('o')
    identity = 'NT AUTHORITY\SYSTEM'
    executionTimeLimitMinutes = 10
    serviceStateBefore = 'Stopped'
    bind = '127.0.0.1:18080'
}
$result | ConvertTo-Json | Set-Content -LiteralPath `
    (Join-Path $evidenceRoot 'scheduled-task-registration.json') `
    -Encoding utf8

Write-Output "REGISTERED: task=$TaskName runAt=$($scheduledAt.ToString('yyyy-MM-dd HH:mm:ss zzz')) identity=SYSTEM"
Write-Output 'ACTION: log off the RDP session before the scheduled time; reconnect after at least 6 minutes.'
