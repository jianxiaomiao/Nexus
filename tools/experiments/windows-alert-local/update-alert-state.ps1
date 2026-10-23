param(
    [Parameter(Mandatory)]
    [string]$HostId,
    [Parameter(Mandatory)]
    [string]$ServiceName,
    [Parameter(Mandatory)]
    [string]$FailureType,
    [Parameter(Mandatory)]
    [ValidateSet('Healthy', 'Failed')]
    [string]$Observation,
    [ValidateSet('Info', 'Warning', 'Critical')]
    [string]$Severity = 'Critical',
    [ValidateRange(1, 100)]
    [int]$FailureThreshold = 3,
    [ValidateRange(1, 100)]
    [int]$RecoveryThreshold = 2,
    [string]$Message = '',
    [switch]$Maintenance,
    [Parameter(Mandatory)]
    [string]$StateRoot,
    [Parameter(Mandatory)]
    [string]$OutboxPath,
    [DateTimeOffset]$ObservedAt = [DateTimeOffset]::Now
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'This experiment requires PowerShell 7 or later.'
}

function Get-Sha256Hex([string]$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Enter-ExclusiveFile([string]$Path, [int]$TimeoutSeconds = 5) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            return [IO.FileStream]::new(
                $Path,
                [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite,
                [IO.FileShare]::None)
        } catch [IO.IOException] {
            if ([DateTime]::UtcNow -ge $deadline) { throw }
            Start-Sleep -Milliseconds 50
        }
    } while ($true)
}

function Save-State([string]$Path, [object]$State) {
    $temporaryPath = "$Path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $json = $State | ConvertTo-Json -Depth 8
        [IO.File]::WriteAllText(
            $temporaryPath,
            $json + [Environment]::NewLine,
            [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function New-Notification(
    [string]$Kind,
    [string]$IncidentId,
    [object]$State,
    [string]$Timestamp,
    [string]$NotificationMessage) {
    return [ordered]@{
        schemaVersion = 1
        eventId = [guid]::NewGuid().ToString()
        incidentId = $IncidentId
        dedupeKey = $State.dedupeKey
        kind = $Kind
        severity = if ($Kind -eq 'RESOLVED') { 'Info' } else { $State.severity }
        hostId = $State.hostId
        serviceName = $State.serviceName
        failureType = $State.failureType
        firstSeen = $State.firstSeen
        lastSeen = $Timestamp
        occurrences = $State.occurrences
        message = $NotificationMessage
    }
}

function Flush-PendingNotifications(
    [string]$StatePath,
    [object]$State,
    [string]$NotificationOutboxPath) {
    $pending = @($State.pendingNotifications)
    if ($pending.Count -eq 0) { return 0 }

    $outboxLock = Enter-ExclusiveFile "$NotificationOutboxPath.lock"
    try {
        $writtenEventIds = [Collections.Generic.HashSet[string]]::new(
            [StringComparer]::OrdinalIgnoreCase)
        if (Test-Path -LiteralPath $NotificationOutboxPath) {
            foreach ($line in Get-Content -LiteralPath $NotificationOutboxPath) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $existing = $line | ConvertFrom-Json
                [void]$writtenEventIds.Add([string]$existing.eventId)
            }
        }

        $written = 0
        foreach ($notification in $pending) {
            if ($writtenEventIds.Add([string]$notification.eventId)) {
                $jsonLine = $notification | ConvertTo-Json -Depth 6 -Compress
                [IO.File]::AppendAllText(
                    $NotificationOutboxPath,
                    $jsonLine + [Environment]::NewLine,
                    [Text.UTF8Encoding]::new($false))
                $written++
            }
        }
    } finally {
        $outboxLock.Dispose()
    }

    $State.pendingNotifications = @()
    Save-State $StatePath $State
    return $written
}

$stateRootPath = [IO.Path]::GetFullPath($StateRoot)
$notificationOutboxPath = [IO.Path]::GetFullPath($OutboxPath)
$outboxRoot = Split-Path -Parent $notificationOutboxPath
New-Item -ItemType Directory -Path $stateRootPath, $outboxRoot -Force |
    Out-Null

$dedupeKey = "$HostId/$ServiceName/$FailureType"
$stateKey = Get-Sha256Hex $dedupeKey
$statePath = Join-Path $stateRootPath "$stateKey.json"
$stateLockPath = Join-Path $stateRootPath "$stateKey.lock"
$observedAtText = $ObservedAt.ToString('o')
$stateLock = Enter-ExclusiveFile $stateLockPath

try {
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if ($state.dedupeKey -ne $dedupeKey) {
            throw 'The stored state does not match the requested dedupe key.'
        }
        $state.pendingNotifications = @($state.pendingNotifications)
    } else {
        $state = [pscustomobject][ordered]@{
            schemaVersion = 1
            dedupeKey = $dedupeKey
            hostId = $HostId
            serviceName = $ServiceName
            failureType = $FailureType
            severity = $Severity
            status = 'CLOSED'
            incidentId = $null
            lastIncidentId = $null
            firstSeen = $null
            lastSeen = $null
            consecutiveFailures = 0
            consecutiveSuccesses = 0
            occurrences = 0
            lastMessage = ''
            pendingNotifications = @()
        }
    }

    $action = 'NONE'
    if ($Maintenance) {
        if ($state.status -eq 'OPEN') {
            throw 'Cannot suppress an incident that is already open; resolve it first.'
        }
        $state.consecutiveFailures = 0
        $state.consecutiveSuccesses = 0
        $state.occurrences = 0
        $state.firstSeen = $null
        $state.lastSeen = $observedAtText
        $state.lastMessage = $Message
        $action = 'SUPPRESSED_MAINTENANCE'
    } elseif ($Observation -eq 'Failed') {
        $state.lastSeen = $observedAtText
        $state.lastMessage = $Message
        $state.consecutiveSuccesses = 0
        $state.consecutiveFailures++
        $state.occurrences++
        if ($state.consecutiveFailures -eq 1 -and $state.status -eq 'CLOSED') {
            $state.firstSeen = $observedAtText
            $state.severity = $Severity
        }

        if ($state.status -eq 'CLOSED' -and
            $state.consecutiveFailures -ge $FailureThreshold) {
            $state.status = 'OPEN'
            $state.incidentId = [guid]::NewGuid().ToString()
            $notification = New-Notification 'OPEN' $state.incidentId `
                $state $observedAtText $Message
            $state.pendingNotifications = @($state.pendingNotifications) +
                @($notification)
            $action = 'OPENED'
        } elseif ($state.status -eq 'OPEN') {
            $action = 'SUPPRESSED_DUPLICATE'
        }
    } else {
        $state.lastSeen = $observedAtText
        $state.lastMessage = $Message
        $state.consecutiveFailures = 0
        if ($state.status -eq 'OPEN') {
            $state.consecutiveSuccesses++
            if ($state.consecutiveSuccesses -ge $RecoveryThreshold) {
                $notification = New-Notification 'RESOLVED' $state.incidentId `
                    $state $observedAtText $Message
                $state.pendingNotifications = @($state.pendingNotifications) +
                    @($notification)
                $state.status = 'CLOSED'
                $state.lastIncidentId = $state.incidentId
                $state.incidentId = $null
                $state.firstSeen = $null
                $state.consecutiveSuccesses = 0
                $state.occurrences = 0
                $action = 'RESOLVED'
            }
        } else {
            $state.consecutiveSuccesses = 0
            $state.occurrences = 0
            $state.firstSeen = $null
        }
    }

    Save-State $statePath $state
    $writtenNotifications = Flush-PendingNotifications `
        $statePath $state $notificationOutboxPath

    [pscustomobject]@{
        Action = $action
        DedupeKey = $dedupeKey
        Status = $state.status
        IncidentId = $state.incidentId
        LastIncidentId = $state.lastIncidentId
        ConsecutiveFailures = $state.consecutiveFailures
        ConsecutiveSuccesses = $state.consecutiveSuccesses
        Occurrences = $state.occurrences
        NotificationsWritten = $writtenNotifications
        StatePath = $statePath
        OutboxPath = $notificationOutboxPath
    }
} finally {
    $stateLock.Dispose()
}
