$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'Run this experiment with PowerShell 7 or later.'
}

$experimentRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$runtimeRoot = Join-Path $experimentRoot 'runtime'
$runId = '{0}-{1}' -f [DateTime]::Now.ToString('yyyyMMdd-HHmmss'),
    [guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $runtimeRoot $runId
$stateRoot = Join-Path $runRoot 'state'
$outboxPath = Join-Path $runRoot 'outbox\notifications.jsonl'
$resultPath = Join-Path $runRoot 'result.txt'
$enginePath = Join-Path $experimentRoot 'update-alert-state.ps1'
$pwshPath = (Get-Process -Id $PID).Path
$hostId = 'local-lab-host'
$serviceName = 'NexusNginxLab'
$failureType = 'service-unavailable'
$baseTime = [DateTimeOffset]::Now

New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

function Invoke-Observation(
    [string]$Observation,
    [int]$OffsetSeconds,
    [string]$Message,
    [switch]$Maintenance) {
    $arguments = @{
        HostId = $hostId
        ServiceName = $serviceName
        FailureType = $failureType
        Observation = $Observation
        Severity = 'Critical'
        FailureThreshold = 3
        RecoveryThreshold = 2
        Message = $Message
        StateRoot = $stateRoot
        OutboxPath = $outboxPath
        ObservedAt = $baseTime.AddSeconds($OffsetSeconds)
    }
    if ($Maintenance) { $arguments.Maintenance = $true }
    return & $enginePath @arguments
}

function Read-Outbox {
    if (-not (Test-Path -LiteralPath $outboxPath)) { return @() }
    return @(Get-Content -LiteralPath $outboxPath | ForEach-Object {
        if (-not [string]::IsNullOrWhiteSpace($_)) { $_ | ConvertFrom-Json }
    })
}

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

# Two failures followed by health must not open an incident.
[void](Invoke-Observation 'Failed' 1 'transient-1')
[void](Invoke-Observation 'Failed' 2 'transient-2')
[void](Invoke-Observation 'Healthy' 3 'transient-recovered')
Assert-Equal (Read-Outbox).Count 0 'Transient failures created a notification.'

# Twelve concurrent failures must create exactly one OPEN notification.
$children = @()
for ($index = 1; $index -le 12; $index++) {
    $stdoutPath = Join-Path $runRoot "child-$index.out.log"
    $stderrPath = Join-Path $runRoot "child-$index.err.log"
    $arguments = @(
        '-NoLogo', '-NoProfile', '-NonInteractive', '-File', $enginePath,
        '-HostId', $hostId,
        '-ServiceName', $serviceName,
        '-FailureType', $failureType,
        '-Observation', 'Failed',
        '-Severity', 'Critical',
        '-FailureThreshold', '3',
        '-RecoveryThreshold', '2',
        '-Message', "concurrent-$index",
        '-StateRoot', $stateRoot,
        '-OutboxPath', $outboxPath,
        '-ObservedAt', $baseTime.AddSeconds(10).ToString('o'))
    $children += Start-Process -FilePath $pwshPath -ArgumentList $arguments `
        -NoNewWindow -PassThru -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath
}
foreach ($child in $children) {
    $child.WaitForExit()
    if ($child.ExitCode -ne 0) {
        $stderrPath = Join-Path $runRoot `
            "child-$($children.IndexOf($child) + 1).err.log"
        $details = Get-Content -LiteralPath $stderrPath -Raw
        throw "Concurrent observation failed with exit code $($child.ExitCode): $details"
    }
}

$afterConcurrent = Read-Outbox
Assert-Equal $afterConcurrent.Count 1 `
    'Concurrent failures did not produce exactly one OPEN notification.'
Assert-Equal $afterConcurrent[0].kind 'OPEN' `
    'The first notification was not OPEN.'
$firstIncidentId = [string]$afterConcurrent[0].incidentId
$parsedGuid = [guid]::Empty
if (-not [guid]::TryParse($firstIncidentId, [ref]$parsedGuid)) {
    throw 'The first incident ID is not a valid UUID.'
}

$statePath = (Get-ChildItem -LiteralPath $stateRoot -Filter '*.json' |
    Select-Object -First 1).FullName
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
Assert-Equal $state.occurrences 12 `
    'Concurrent observations were lost while updating state.'

# One success is insufficient; the second resolves with the same incident ID.
$firstSuccess = Invoke-Observation 'Healthy' 20 'health-1'
Assert-Equal $firstSuccess.Action 'NONE' `
    'One healthy observation resolved the incident too early.'
$secondSuccess = Invoke-Observation 'Healthy' 21 'health-2'
Assert-Equal $secondSuccess.Action 'RESOLVED' `
    'Two healthy observations did not resolve the incident.'
$afterResolve = Read-Outbox
Assert-Equal $afterResolve.Count 2 `
    'Resolution did not add exactly one notification.'
Assert-Equal $afterResolve[1].incidentId $firstIncidentId `
    'OPEN and RESOLVED did not share one incident ID.'
Assert-Equal $afterResolve[1].kind 'RESOLVED' `
    'The second notification was not RESOLVED.'

# Planned maintenance failures are observed but never open an incident.
for ($index = 1; $index -le 5; $index++) {
    $maintenanceResult = Invoke-Observation 'Failed' (30 + $index) `
        "maintenance-$index" -Maintenance
    Assert-Equal $maintenanceResult.Action 'SUPPRESSED_MAINTENANCE' `
        'A maintenance observation was not suppressed.'
}
Assert-Equal (Read-Outbox).Count 2 `
    'Maintenance observations created notifications.'

# A later failure is a new incident with a new UUID.
[void](Invoke-Observation 'Failed' 40 'second-1')
[void](Invoke-Observation 'Failed' 41 'second-2')
$secondOpen = Invoke-Observation 'Failed' 42 'second-3'
Assert-Equal $secondOpen.Action 'OPENED' 'The second incident did not open.'
$withSecondIncident = Read-Outbox
Assert-Equal $withSecondIncident.Count 3 `
    'The second incident did not create exactly one OPEN notification.'
$secondIncidentId = [string]$withSecondIncident[2].incidentId
if ($secondIncidentId -eq $firstIncidentId) {
    throw 'A new incident reused the previous incident UUID.'
}
try {
    [void](Invoke-Observation 'Failed' 42 'late-maintenance' -Maintenance)
    throw 'Maintenance unexpectedly hid an already open incident.'
} catch {
    if ($_.Exception.Message -notlike 'Cannot suppress an incident*') {
        throw
    }
}
[void](Invoke-Observation 'Healthy' 43 'second-health-1')
[void](Invoke-Observation 'Healthy' 44 'second-health-2')

$finalOutbox = Read-Outbox
Assert-Equal $finalOutbox.Count 4 `
    'The final outbox does not contain two OPEN/RESOLVED pairs.'
$eventIds = @($finalOutbox.eventId | Sort-Object -Unique)
Assert-Equal $eventIds.Count 4 'Notification event IDs are not unique.'

$result = @"
PASS
runId=$runId
failureThreshold=3
recoveryThreshold=2
concurrentFailures=12
firstIncidentId=$firstIncidentId
firstIncidentNotifications=2
maintenanceFailuresSuppressed=5
openIncidentMaintenanceRejected=true
secondIncidentId=$secondIncidentId
secondIncidentNotifications=2
uniqueEventIds=4
totalNotifications=4
"@
[IO.File]::WriteAllText(
    $resultPath,
    $result + "`n",
    [Text.UTF8Encoding]::new($false))

Write-Output 'TRANSIENT: two failures followed by health produced no notification.'
Write-Output "DEDUP: 12 concurrent failures produced one OPEN incident=$firstIncidentId."
Write-Output 'RECOVERY: two healthy observations produced one RESOLVED notification with the same incident ID.'
Write-Output 'MAINTENANCE: five planned failures produced no notification.'
Write-Output "NEW-INCIDENT: later failure used a new incident=$secondIncidentId."
Write-Output "PASS: notificationCount=4 evidence=$runRoot"
