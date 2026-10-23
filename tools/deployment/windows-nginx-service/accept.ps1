param(
    [string]$ServiceName = 'NexusNginx',
    [string]$DeploymentRoot = 'C:\NexusDeploy'
)

$ErrorActionPreference = 'Stop'
$deploymentRoot = [IO.Path]::GetFullPath($DeploymentRoot)
$nginxRoot = Join-Path $deploymentRoot 'shared\nginx\nginx-1.31.6'
$nginxPath = Join-Path $nginxRoot 'nginx.exe'
$configPath = Join-Path $deploymentRoot 'config\nginx\legacy-library-service.conf'
$distRoot = Join-Path $deploymentRoot 'candidates\legacy-library\V2026.10.21.1__nginx-relative-paths\dist'
$serviceRoot = Join-Path $deploymentRoot 'services\nginx'
$configRoot = Join-Path $deploymentRoot 'config\nginx'
$nginxLogsRoot = Join-Path $deploymentRoot 'logs\nginx'
$serviceLogsRoot = Join-Path $deploymentRoot 'logs\service\nginx'
$runRoot = Join-Path $deploymentRoot 'run\nginx'
$tempRoot = Join-Path $deploymentRoot 'temp\nginx'
$cleanupAuditPath = Join-Path $serviceLogsRoot 'recovery-cleanup.log'
$evidenceRoot = Join-Path $deploymentRoot 'evidence\windows-nginx-service'
$serviceIdentityName = "NT SERVICE\$ServiceName"
$port = 18080

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Acceptance must run in an elevated Administrator PowerShell 7 session.'
    }
}

function Wait-ServiceState([string]$Expected, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $service = Get-Service -Name $ServiceName -ErrorAction Stop
        if ($service.Status.ToString() -eq $Expected) { return }
        Start-Sleep -Milliseconds 250
    }
    throw "Service $ServiceName did not reach $Expected within $Seconds seconds."
}

function Get-ServiceNginxProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" |
        Where-Object {
            [string]::Equals($_.ExecutablePath, $nginxPath,
                [StringComparison]::OrdinalIgnoreCase) -and
            $null -ne $_.CommandLine -and
            $_.CommandLine.IndexOf($nginxRoot.Replace('\', '/') + '/',
                [StringComparison]::OrdinalIgnoreCase) -ge 0
        })
}

function Get-NginxTopology {
    $service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    if ($null -eq $service -or $service.ProcessId -eq 0) { return $null }
    $supervisor = Get-CimInstance Win32_Process -Filter "Name='pwsh.exe'" |
        Where-Object { $_.ParentProcessId -eq $service.ProcessId } |
        Select-Object -First 1
    if ($null -eq $supervisor) { return $null }
    $processes = @(Get-ServiceNginxProcesses)
    $master = $processes | Where-Object {
        $_.ParentProcessId -eq $supervisor.ProcessId
    } | Select-Object -First 1
    if ($null -eq $master) { return $null }
    $workers = @($processes | Where-Object {
        $_.ParentProcessId -eq $master.ProcessId
    })
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port `
        -ErrorAction SilentlyContinue | Where-Object {
            $_.LocalAddress -eq '127.0.0.1'
        } | Select-Object -First 1
    $nginxProcessIds = @([int]$master.ProcessId) +
        @($workers.ProcessId | ForEach-Object { [int]$_ })
    if ($workers.Count -eq 0 -or $null -eq $listener -or
        $listener.OwningProcess -notin $nginxProcessIds) {
        return $null
    }
    $owner = Invoke-CimMethod -InputObject $workers[0] -MethodName GetOwner
    return [pscustomobject]@{
        WrapperPid = [int]$service.ProcessId
        SupervisorPid = [int]$supervisor.ProcessId
        MasterPid = [int]$master.ProcessId
        WorkerPids = @($workers.ProcessId | ForEach-Object { [int]$_ })
        ListenerPid = [int]$listener.OwningProcess
        Owner = "$($owner.Domain)\$($owner.User)"
    }
}

function Wait-NginxTopology([int]$Seconds, [int]$DifferentMasterPid = 0) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $topology = Get-NginxTopology
        if ($null -ne $topology -and
            ($DifferentMasterPid -eq 0 -or
                $topology.MasterPid -ne $DifferentMasterPid)) {
            return $topology
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Expected Nginx topology did not appear on 127.0.0.1:$port."
}

function Wait-Http([int]$Seconds, [string]$ExpectedHash) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    $last = 'no request completed'
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $handler = [Net.Http.HttpClientHandler]::new()
            $handler.UseProxy = $false
            $client = [Net.Http.HttpClient]::new($handler)
            $client.Timeout = [TimeSpan]::FromSeconds(3)
            try {
                $response = $client.GetAsync("http://127.0.0.1:$port/").GetAwaiter().GetResult()
                $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                $sha256 = [Security.Cryptography.SHA256]::Create()
                try {
                    $actualHash = ([BitConverter]::ToString(
                        $sha256.ComputeHash($bytes))).Replace('-', '')
                } finally {
                    $sha256.Dispose()
                }
            } finally {
                $client.Dispose()
                $handler.Dispose()
            }
            $status = [int]$response.StatusCode
            $last = "status=$status hash=$actualHash"
            if ($status -eq 200 -and $actualHash -eq $ExpectedHash) {
                return
            }
        } catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Nginx health check failed. Last observation: $last"
}

function Assert-OnlyLoopbackListener {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port `
        -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1') {
        throw "Port $port is not bound exclusively to IPv4 loopback."
    }
}

function Assert-ServiceAcl(
    [string]$Path,
    [Security.Principal.SecurityIdentifier]$ServiceSid,
    [bool]$Writable) {
    $rootPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $allowedSids = @('S-1-5-18', 'S-1-5-32-544', $ServiceSid.Value)
    $writeMask = [Security.AccessControl.FileSystemRights]::Write
    $modifyMask = [Security.AccessControl.FileSystemRights]::Modify
    $items = @((Get-Item -LiteralPath $Path -Force)) +
        @(Get-ChildItem -LiteralPath $Path -Recurse -Force)

    foreach ($item in $items) {
        $acl = Get-Acl -LiteralPath $item.FullName
        $itemPath = [IO.Path]::GetFullPath($item.FullName).TrimEnd('\')
        if ($itemPath -eq $rootPath -and -not $acl.AreAccessRulesProtected) {
            throw "ACL inheritance is unexpectedly enabled on controlled root: $($item.FullName)"
        }
        $serviceHasRequiredRule = $false
        foreach ($rule in $acl.Access) {
            $ruleSid = $rule.IdentityReference.Translate(
                [Security.Principal.SecurityIdentifier])
            if ($rule.AccessControlType -ne
                [Security.AccessControl.AccessControlType]::Allow -or
                $ruleSid.Value -notin $allowedSids) {
                throw "Unexpected ACL entry on $($item.FullName): $($rule.IdentityReference) $($rule.AccessControlType) $($rule.FileSystemRights)"
            }
            if ($ruleSid -eq $ServiceSid) {
                if ($Writable -and
                    (($rule.FileSystemRights -band $modifyMask) -eq $modifyMask)) {
                    $serviceHasRequiredRule = $true
                } elseif (-not $Writable -and
                    (($rule.FileSystemRights -band $writeMask) -eq 0)) {
                    $serviceHasRequiredRule = $true
                }
                if (-not $Writable -and
                    (($rule.FileSystemRights -band $writeMask) -ne 0)) {
                    throw "Service account has write access to read-only path: $($item.FullName)"
                }
            }
        }
        if (-not $serviceHasRequiredRule) {
            throw "Required service ACL is missing: $($item.FullName)"
        }
    }
}

Assert-Administrator
$service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
if ($null -eq $service) { throw "Service $ServiceName is not installed." }
if ($service.StartMode -ne 'Manual' -or
    $service.StartName -ne $serviceIdentityName) {
    throw "Unexpected service configuration: mode=$($service.StartMode) account=$($service.StartName)"
}
$serviceSid = ([Security.Principal.NTAccount]::new(
    $serviceIdentityName)).Translate([Security.Principal.SecurityIdentifier])
foreach ($readOnlyRoot in @($nginxRoot, $configRoot, $distRoot, $serviceRoot)) {
    Assert-ServiceAcl $readOnlyRoot $serviceSid $false
}
foreach ($writableRoot in @($nginxLogsRoot, $serviceLogsRoot, $runRoot, $tempRoot)) {
    Assert-ServiceAcl $writableRoot $serviceSid $true
}

$operatingSystem = Get-CimInstance Win32_OperatingSystem
$freePhysicalMemoryMb = [Math]::Round(
    ([double]$operatingSystem.FreePhysicalMemory / 1KB), 1)
if ($freePhysicalMemoryMb -lt 256) {
    throw "Only $freePhysicalMemoryMb MiB physical memory is currently free. Acceptance did not start the service because the transient safety floor is 256 MiB."
}

$indexHash = (Get-FileHash -LiteralPath (Join-Path $distRoot 'index.html') `
    -Algorithm SHA256).Hash
$portsBefore = @(Get-NetTCPConnection -State Listen -LocalPort 80,443 `
    -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess |
    Sort-Object LocalPort,LocalAddress,OwningProcess)

$result = [ordered]@{}
$result.freePhysicalMemoryBeforeMb = $freePhysicalMemoryMb
$curlProcess = $null
try {
    Start-Service -Name $ServiceName
    Wait-ServiceState 'Running' 20
    Wait-Http 30 $indexHash
    $first = Wait-NginxTopology 20
    Assert-OnlyLoopbackListener
    if (-not [string]::Equals($first.Owner, $serviceIdentityName,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Nginx runs as the wrong identity: $($first.Owner)"
    }
    $result.initialStart = $first

    Stop-Service -Name $ServiceName
    Wait-ServiceState 'Stopped' 150
    Start-Sleep -Seconds 3
    if (@(Get-ServiceNginxProcesses).Count -ne 0 -or
        (Get-NetTCPConnection -State Listen -LocalPort $port `
            -ErrorAction SilentlyContinue)) {
        throw 'Normal stop left a matching Nginx process or listener.'
    }
    $result.normalStopStayedStopped = $true

    Start-Service -Name $ServiceName
    Wait-ServiceState 'Running' 20
    Wait-Http 30 $indexHash
    $beforeRestart = Wait-NginxTopology 20
    Restart-Service -Name $ServiceName
    Wait-ServiceState 'Running' 150
    $afterRestart = Wait-NginxTopology 40 $beforeRestart.MasterPid
    Wait-Http 30 $indexHash
    Assert-OnlyLoopbackListener
    $result.manualRestart = [ordered]@{
        oldMasterPid = $beforeRestart.MasterPid
        newMasterPid = $afterRestart.MasterPid
    }

    $oldMasterPid = $afterRestart.MasterPid
    $oldWorkerPids = @($afterRestart.WorkerPids)
    Stop-Process -Id $oldMasterPid -Force
    $afterRecovery = Wait-NginxTopology 80 $oldMasterPid
    Wait-ServiceState 'Running' 20
    Wait-Http 30 $indexHash
    Assert-OnlyLoopbackListener
    $oldDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $remaining = @(Get-ServiceNginxProcesses | Where-Object {
            $_.ProcessId -in $oldWorkerPids
        })
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $oldDeadline)
    if ($remaining.Count -ne 0) {
        throw 'Abnormal recovery left an old worker running.'
    }
    $auditLines = @(Get-Content -LiteralPath $cleanupAuditPath)
    foreach ($oldWorkerPid in $oldWorkerPids) {
        if (-not ($auditLines | Where-Object {
                    $_ -match "action=killed pids=(?:[0-9]+,)*$oldWorkerPid(?:,[0-9]+)*$"
                })) {
            throw "Recovery audit did not record old worker PID $oldWorkerPid."
        }
    }
    $result.abnormalRecovery = [ordered]@{
        killedMasterPid = $oldMasterPid
        cleanedWorkerPids = $oldWorkerPids
        recoveredMasterPid = $afterRecovery.MasterPid
    }

    # Hold the existing anonymous message SSE route open while reloading.
    # The old worker must remain during graceful shutdown, then exit at the
    # configured 120-second upper bound even though the client stays connected.
    $sseUser = 'nginx-service-accept-' + [guid]::NewGuid().ToString('N')
    $curlOut = Join-Path $evidenceRoot 'sse-curl.stdout.txt'
    $curlErr = Join-Path $evidenceRoot 'sse-curl.stderr.txt'
    $curlProcess = Start-Process -FilePath "$env:SystemRoot\System32\curl.exe" `
        -ArgumentList @('--no-buffer', '--max-time', '180',
            "http://127.0.0.1:$port/tsglxt/api/message/sse?userId=$sseUser") `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $curlOut -RedirectStandardError $curlErr

    $connectionDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $clientConnection = Get-NetTCPConnection -State Established `
            -RemotePort $port -OwningProcess $curlProcess.Id `
            -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $clientConnection) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $connectionDeadline)
    if ($null -eq $clientConnection) {
        throw 'The local SSE acceptance client did not establish a connection.'
    }

    $beforeReload = Get-NginxTopology
    $oldGracefulWorkerPids = @($beforeReload.WorkerPids)
    $reloadStartedAt = [DateTime]::UtcNow
    & $nginxPath -p ($nginxRoot.Replace('\', '/') + '/') `
        -c $configPath.Replace('\', '/') `
        -e (Join-Path $serviceLogsRoot 'nginx-signal-error.log').Replace('\', '/') `
        -s reload
    if ($LASTEXITCODE -ne 0) { throw "Nginx reload failed with exit code $LASTEXITCODE." }

    $newWorkerDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $afterReload = Get-NginxTopology
        $newWorkerPids = @($afterReload.WorkerPids | Where-Object {
            $_ -notin $oldGracefulWorkerPids
        })
        if ($newWorkerPids.Count -gt 0) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $newWorkerDeadline)
    if ($newWorkerPids.Count -eq 0) {
        throw 'Reload did not create a new worker.'
    }

    $oldWorkerDeadline = $reloadStartedAt.AddSeconds(140)
    do {
        $oldGracefulWorkers = @(Get-ServiceNginxProcesses | Where-Object {
            $_.ProcessId -in $oldGracefulWorkerPids
        })
        if ($oldGracefulWorkers.Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $oldWorkerDeadline)
    if ($oldGracefulWorkers.Count -ne 0) {
        throw 'Old worker exceeded the 140-second acceptance bound.'
    }
    $gracefulSeconds = [Math]::Round(
        ([DateTime]::UtcNow - $reloadStartedAt).TotalSeconds, 1)
    if ($gracefulSeconds -lt 110) {
        throw "Old worker exited after only $gracefulSeconds seconds; the 120-second shutdown bound was not exercised."
    }
    $result.workerShutdownTimeout = [ordered]@{
        configuredSeconds = 120
        observedSeconds = $gracefulSeconds
        oldWorkerPids = $oldGracefulWorkerPids
        newWorkerPids = $newWorkerPids
    }

    Wait-Http 30 $indexHash
    Assert-OnlyLoopbackListener

    $portsAfter = @(Get-NetTCPConnection -State Listen -LocalPort 80,443 `
        -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess |
        Sort-Object LocalPort,LocalAddress,OwningProcess)
    if (Compare-Object $portsBefore $portsAfter -Property LocalAddress,LocalPort,OwningProcess) {
        throw 'The 80/443 listener snapshot changed during isolated acceptance.'
    }

    $result.publicPortSnapshotUnchanged = $true
    $result.finalHttpHealthy = $true
} finally {
    if ($null -ne $curlProcess -and -not $curlProcess.HasExited) {
        Stop-Process -Id $curlProcess.Id -Force -ErrorAction SilentlyContinue
    }
    $installedService = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($null -ne $installedService -and $installedService.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
        try { Wait-ServiceState 'Stopped' 150 } catch {}
    }
}

Start-Sleep -Seconds 3
if ((Get-Service -Name $ServiceName).Status -ne 'Stopped' -or
    @(Get-ServiceNginxProcesses).Count -ne 0 -or
    (Get-NetTCPConnection -State Listen -LocalPort $port `
        -ErrorAction SilentlyContinue)) {
    throw 'Final cleanup left a matching Nginx process or port 18080 listener.'
}
$result.finalStatus = 'Stopped'
$result.startMode = 'Manual'
$result.serviceAccount = $serviceIdentityName
$serviceErrorLogPath = Join-Path $serviceLogsRoot "$ServiceName.err.log"
if ((Test-Path -LiteralPath $serviceErrorLogPath) -and
    (Get-Item -LiteralPath $serviceErrorLogPath).Length -ne 0) {
    throw 'The Nginx supervisor wrote unexpected WinSW stderr output.'
}
$result.serviceStderrEmpty = $true
$result.completedAt = [DateTimeOffset]::Now.ToString('o')
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath `
    (Join-Path $evidenceRoot 'accept-result.json') -Encoding utf8

Write-Output "PASS: $ServiceName runs as $serviceIdentityName with loopback-only Nginx, bounded recovery, and a verified 120-second graceful-worker limit."
Write-Output "FINAL: service=Stopped mode=Manual port18080Listeners=0 publicPortsUnchanged=true"
