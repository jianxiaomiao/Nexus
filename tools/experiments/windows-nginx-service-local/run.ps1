param(
    [string]$ServiceName = 'NexusNginxLab',
    [ValidateRange(1024, 65535)]
    [int]$Port = 18084
)

$ErrorActionPreference = 'Stop'
$expectedWinSwHash = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$expectedNginxHash = '2429269C8EA20CE6C5040BB4EB5FD78A28003DFA272F66D146338320353C5F62'
$experimentRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $experimentRoot '../../..'))
$runtimeRoot = Join-Path $experimentRoot 'runtime'
$labRoot = Join-Path $runtimeRoot 'lab'
$serviceRoot = Join-Path $labRoot 'service'
$nginxRoot = Join-Path $labRoot 'nginx'
$configRoot = Join-Path $labRoot 'config'
$distRoot = Join-Path $labRoot 'dist'
$logsRoot = Join-Path $labRoot 'logs'
$nginxLogsRoot = Join-Path $logsRoot 'nginx'
$serviceLogsRoot = Join-Path $logsRoot 'service'
$runRoot = Join-Path $labRoot 'run'
$tempRoot = Join-Path $labRoot 'temp'
$evidenceRoot = Join-Path $labRoot 'evidence'
$wrapperPath = Join-Path $serviceRoot "$ServiceName.exe"
$wrapperConfigPath = Join-Path $serviceRoot "$ServiceName.xml"
$supervisorPath = Join-Path $serviceRoot 'nginx-supervisor.ps1'
$cleanupAuditPath = Join-Path $serviceLogsRoot 'recovery-cleanup.log'
$nginxPath = Join-Path $nginxRoot 'nginx.exe'
$nginxConfigPath = Join-Path $configRoot 'nginx.conf'
$powerShell7Path = 'C:\Program Files\PowerShell\7\pwsh.exe'
$sourceWinSwPath = Join-Path $repoRoot `
    'tools\experiments\windows-service-local\runtime\WinSW-x64-2.12.0.exe'
$sourceNginxPath = 'D:\nginx\nginx-1.31.6\nginx.exe'
$sourceMimeTypesPath = 'D:\nginx\nginx-1.31.6\conf\mime.types'
$sourceDistRoot = 'D:\NexusDeploy\current\console\dist'
$serviceIdentityName = "NT SERVICE\$ServiceName"

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This experiment must run in an elevated Administrator PowerShell.'
    }
}

function Write-Utf8Text([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function ConvertTo-NginxPath([string]$Path) {
    return ([IO.Path]::GetFullPath($Path)).Replace('\', '/')
}

function ConvertTo-PowerShellLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Add-DirectoryRule(
    [Security.AccessControl.DirectorySecurity]$Acl,
    [Security.Principal.IdentityReference]$Identity,
    [Security.AccessControl.FileSystemRights]$Rights) {
    $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $propagation = [Security.AccessControl.PropagationFlags]::None
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $Identity, $Rights, $inheritance, $propagation,
        [Security.AccessControl.AccessControlType]::Allow)
    [void]$Acl.AddAccessRule($rule)
}

function Set-RestrictedLabAcl([Security.Principal.SecurityIdentifier]$ServiceSid) {
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $administratorsSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    Add-DirectoryRule $acl $currentSid ([Security.AccessControl.FileSystemRights]::FullControl)
    Add-DirectoryRule $acl $systemSid ([Security.AccessControl.FileSystemRights]::FullControl)
    Add-DirectoryRule $acl $administratorsSid ([Security.AccessControl.FileSystemRights]::FullControl)
    Add-DirectoryRule $acl $ServiceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    Set-Acl -LiteralPath $labRoot -AclObject $acl

    foreach ($writablePath in @($logsRoot, $runRoot, $tempRoot)) {
        $writableAcl = Get-Acl -LiteralPath $writablePath
        Add-DirectoryRule $writableAcl $ServiceSid `
            ([Security.AccessControl.FileSystemRights]::Modify)
        Set-Acl -LiteralPath $writablePath -AclObject $writableAcl
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

function Get-LabNginxProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" `
        -ErrorAction SilentlyContinue | Where-Object {
            $_.ExecutablePath -eq $nginxPath -or
            $_.CommandLine -like "*$labRoot*"
        })
}

function Get-NginxTopology {
    $service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'" `
        -ErrorAction SilentlyContinue
    if ($null -eq $service -or $service.ProcessId -eq 0) { return $null }
    $supervisor = Get-CimInstance Win32_Process -Filter "Name='pwsh.exe'" `
        -ErrorAction SilentlyContinue | Where-Object {
            $_.ParentProcessId -eq $service.ProcessId -and
            $_.ExecutablePath -eq $powerShell7Path -and
            $_.CommandLine -like "*$supervisorPath*"
        } | Select-Object -First 1
    if ($null -eq $supervisor) { return $null }
    $processes = @(Get-LabNginxProcesses)
    $master = $processes | Where-Object {
        $_.ParentProcessId -eq $supervisor.ProcessId
    } | Select-Object -First 1
    if ($null -eq $master) { return $null }
    $worker = $processes | Where-Object {
        $_.ParentProcessId -eq $master.ProcessId
    } | Select-Object -First 1
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue | Where-Object {
            $_.LocalAddress -eq '127.0.0.1'
        } | Select-Object -First 1
    if ($null -eq $worker -or $null -eq $listener -or
        $listener.OwningProcess -notin @($master.ProcessId, $worker.ProcessId)) {
        return $null
    }
    $owner = Invoke-CimMethod -InputObject $worker -MethodName GetOwner
    return [pscustomobject]@{
        WrapperPid = [int]$service.ProcessId
        SupervisorPid = [int]$supervisor.ProcessId
        MasterPid = [int]$master.ProcessId
        WorkerPid = [int]$worker.ProcessId
        ListenerPid = [int]$listener.OwningProcess
        Owner = "$($owner.Domain)\$($owner.User)"
    }
}

function Wait-NginxTopology([int]$Seconds, [int]$DifferentFromMasterPid = 0) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $topology = Get-NginxTopology
        if ($null -ne $topology -and
            ($DifferentFromMasterPid -eq 0 -or
                $topology.MasterPid -ne $DifferentFromMasterPid)) {
            return $topology
        }
        Start-Sleep -Milliseconds 250
    }
    throw "The expected Nginx process tree did not appear on 127.0.0.1:$Port."
}

function Get-HttpEvidence {
    $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/")
    $request.Proxy = $null
    $request.Timeout = 2000
    $request.ReadWriteTimeout = 2000
    $request.AllowAutoRedirect = $false
    $response = $request.GetResponse()
    try {
        $stream = $response.GetResponseStream()
        $memory = [IO.MemoryStream]::new()
        try {
            $stream.CopyTo($memory)
            $bytes = $memory.ToArray()
        } finally {
            $memory.Dispose()
            $stream.Dispose()
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Bytes = $bytes
            HasMarker = $response.Headers['X-Nexus-Nginx-Lab'] -eq 'true'
        }
    } finally {
        $response.Close()
    }
}

function Get-Sha256Hex([byte[]]$Bytes) {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($Bytes))).Replace('-', '')
    } finally {
        $sha256.Dispose()
    }
}

function Wait-Health([int]$Seconds, [string]$ExpectedIndexHash) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    $lastObservation = 'no HTTP attempt completed'
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $evidence = Get-HttpEvidence
            $actualHash = Get-Sha256Hex $evidence.Bytes
            $lastObservation = "status=$($evidence.Status) marker=$($evidence.HasMarker) hash=$actualHash"
            if ($evidence.Status -eq 200 -and $evidence.HasMarker -and
                $actualHash -eq $ExpectedIndexHash) {
                return $evidence.Bytes.Length
            }
        } catch {
            $lastObservation = "error=$($_.Exception.Message)"
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Nginx did not serve the exact isolated dist index on 127.0.0.1:$Port. Last observation: $lastObservation"
}

function Assert-OnlyLoopbackListener {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1') {
        throw "Port $Port is not exclusively bound to IPv4 loopback."
    }
}

function Assert-NoServiceWriteAccess(
    [string]$Path,
    [Security.Principal.SecurityIdentifier]$ServiceSid) {
    $writeMask = [Security.AccessControl.FileSystemRights]::Write
    $items = @((Get-Item -LiteralPath $Path)) +
        @(Get-ChildItem -LiteralPath $Path -Recurse -Force)
    foreach ($item in $items) {
        $acl = Get-Acl -LiteralPath $item.FullName
        foreach ($rule in $acl.Access) {
            $ruleSid = $rule.IdentityReference.Translate(
                [Security.Principal.SecurityIdentifier])
            if ($ruleSid -eq $ServiceSid -and
                $rule.AccessControlType -eq
                    [Security.AccessControl.AccessControlType]::Allow -and
                (($rule.FileSystemRights -band $writeMask) -ne 0)) {
                throw "Service account unexpectedly has write access: $($item.FullName)"
            }
        }
    }
}

Assert-Administrator
if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    throw "Service $ServiceName already exists; refusing to overwrite it."
}
if (Test-Path -LiteralPath $labRoot) {
    throw "Lab directory already exists; refusing to overwrite it: $labRoot"
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use."
}
foreach ($requiredFile in @($sourceWinSwPath, $sourceNginxPath,
        $sourceMimeTypesPath, $powerShell7Path,
        (Join-Path $sourceDistRoot 'index.html'))) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}
if ((Get-FileHash -LiteralPath $sourceWinSwPath -Algorithm SHA256).Hash -ne
    $expectedWinSwHash) {
    throw 'Pinned WinSW SHA-256 does not match the recorded value.'
}
if ((Get-FileHash -LiteralPath $sourceNginxPath -Algorithm SHA256).Hash -ne
    $expectedNginxHash) {
    throw 'Pinned Nginx SHA-256 does not match the recorded value.'
}

$operator = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$serviceInstalled = $false

try {
    New-Item -ItemType Directory -Path $serviceRoot, $nginxRoot, $configRoot,
        $distRoot, $nginxLogsRoot, $serviceLogsRoot, $runRoot, $tempRoot,
        $evidenceRoot -Force | Out-Null
    foreach ($tempName in @('client-body', 'proxy', 'fastcgi', 'uwsgi', 'scgi')) {
        New-Item -ItemType Directory -Path (Join-Path $tempRoot $tempName) `
            -Force | Out-Null
    }

    Copy-Item -LiteralPath $sourceWinSwPath -Destination $wrapperPath
    Copy-Item -LiteralPath $sourceNginxPath -Destination $nginxPath
    Copy-Item -LiteralPath $sourceMimeTypesPath `
        -Destination (Join-Path $configRoot 'mime.types')
    Copy-Item -Path (Join-Path $sourceDistRoot '*') -Destination $distRoot `
        -Recurse -Force

    $indexPath = Join-Path $distRoot 'index.html'
    $expectedIndexHash = (Get-FileHash -LiteralPath $indexPath `
        -Algorithm SHA256).Hash
    $prefix = ConvertTo-NginxPath $labRoot
    $configPath = ConvertTo-NginxPath $nginxConfigPath
    $distPath = ConvertTo-NginxPath $distRoot
    $mimePath = ConvertTo-NginxPath (Join-Path $configRoot 'mime.types')
    $accessLogPath = ConvertTo-NginxPath (Join-Path $nginxLogsRoot 'access.log')
    $errorLogPath = ConvertTo-NginxPath (Join-Path $nginxLogsRoot 'error.log')
    $pidPath = ConvertTo-NginxPath (Join-Path $runRoot 'nginx.pid')
    $clientTemp = ConvertTo-NginxPath (Join-Path $tempRoot 'client-body')
    $proxyTemp = ConvertTo-NginxPath (Join-Path $tempRoot 'proxy')
    $fastcgiTemp = ConvertTo-NginxPath (Join-Path $tempRoot 'fastcgi')
    $uwsgiTemp = ConvertTo-NginxPath (Join-Path $tempRoot 'uwsgi')
    $scgiTemp = ConvertTo-NginxPath (Join-Path $tempRoot 'scgi')

    $cleanupNginxPathLiteral = ConvertTo-PowerShellLiteral $nginxPath
    $cleanupLabRootLiteral = ConvertTo-PowerShellLiteral $labRoot
    $cleanupPrefixPathLiteral = ConvertTo-PowerShellLiteral "$prefix/"
    $cleanupConfigPathLiteral = ConvertTo-PowerShellLiteral $configPath
    $cleanupOwnerLiteral = ConvertTo-PowerShellLiteral $serviceIdentityName
    $cleanupAuditPathLiteral = ConvertTo-PowerShellLiteral $cleanupAuditPath

    $supervisorScript = @"
`$ErrorActionPreference = 'Stop'
`$expectedExecutablePath = $cleanupNginxPathLiteral
`$expectedLabRoot = $cleanupLabRootLiteral
`$expectedPrefixPath = $cleanupPrefixPathLiteral
`$nginxConfigPath = $cleanupConfigPathLiteral
`$expectedOwner = $cleanupOwnerLiteral
`$auditPath = $cleanupAuditPathLiteral

function Test-ContainsOrdinalIgnoreCase([string]`$Value, [string]`$Expected) {
    return `$null -ne `$Value -and
        `$Value.IndexOf(`$Expected, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Get-ProcessOwner([Microsoft.Management.Infrastructure.CimInstance]`$Process) {
    `$owner = Invoke-CimMethod -InputObject `$Process -MethodName GetOwner
    if (`$owner.ReturnValue -ne 0 -or [string]::IsNullOrWhiteSpace(`$owner.User)) {
        throw "Cannot resolve owner for Nginx PID `$(`$Process.ProcessId)."
    }
    return "`$(`$owner.Domain)\`$(`$owner.User)"
}

function Get-RelevantNginxProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" |
        Where-Object {
            [string]::Equals(`$_.ExecutablePath, `$expectedExecutablePath,
                [StringComparison]::OrdinalIgnoreCase) -or
            (Test-ContainsOrdinalIgnoreCase `$_.CommandLine `$expectedPrefixPath) -or
            (Test-ContainsOrdinalIgnoreCase `$_.CommandLine `$expectedLabRoot)
        })
}

`$candidates = @(Get-RelevantNginxProcesses)
`$approved = @()
foreach (`$candidate in `$candidates) {
    if (-not [string]::Equals(`$candidate.ExecutablePath, `$expectedExecutablePath,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup: PID `$(`$candidate.ProcessId) has unexpected executable path."
    }
    `$prefixPattern = '(?i)(?:^|\s)-p\s+"?' +
        [regex]::Escape(`$expectedPrefixPath) + '"?(?=\s|$)'
    if (`$null -eq `$candidate.CommandLine -or
        -not [regex]::IsMatch(`$candidate.CommandLine, `$prefixPattern)) {
        throw "Refusing cleanup: PID `$(`$candidate.ProcessId) lacks the exact lab -p prefix."
    }
    `$owner = Get-ProcessOwner `$candidate
    if (-not [string]::Equals(`$owner, `$expectedOwner,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup: PID `$(`$candidate.ProcessId) is owned by `$owner."
    }
    `$approved += `$candidate
}

`$approvedPids = @(`$approved | ForEach-Object { [int]`$_.ProcessId })
foreach (`$processId in `$approvedPids) {
    Stop-Process -Id `$processId -Force -ErrorAction Stop
}

`$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    `$remaining = @(Get-RelevantNginxProcesses)
    if (`$remaining.Count -eq 0) { break }
    Start-Sleep -Milliseconds 200
} while ([DateTime]::UtcNow -lt `$deadline)

if (`$remaining.Count -ne 0) {
    `$remainingPids = (`$remaining.ProcessId | Sort-Object) -join ','
    throw "Refusing new Nginx start: matching old PIDs remain: `$remainingPids"
}

`$action = if (`$approvedPids.Count -eq 0) { 'none' } else { 'killed' }
`$pidText = (`$approvedPids | Sort-Object) -join ','
`$auditLine = 'at={0} action={1} pids={2}' -f `
    [DateTimeOffset]::Now.ToString('o'), `$action, `$pidText
Add-Content -LiteralPath `$auditPath -Value `$auditLine -Encoding utf8

& `$expectedExecutablePath -p `$expectedPrefixPath -c `$nginxConfigPath -g 'daemon off;'
exit `$LASTEXITCODE
"@
    Write-Utf8Text $supervisorPath ($supervisorScript + "`n")
    $supervisorTokens = $null
    $supervisorParseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile(
        $supervisorPath,
        [ref]$supervisorTokens,
        [ref]$supervisorParseErrors) | Out-Null
    if ($supervisorParseErrors.Count -ne 0) {
        $supervisorParseMessage = ($supervisorParseErrors.Message -join '; ')
        throw "Generated Nginx supervisor script is invalid: $supervisorParseMessage"
    }

    $nginxConfig = @"
worker_processes 1;
error_log $errorLogPath info;
pid $pidPath;

events {
    worker_connections 128;
}

http {
    include $mimePath;
    default_type application/octet-stream;
    access_log $accessLogPath;
    client_body_temp_path $clientTemp;
    proxy_temp_path $proxyTemp;
    fastcgi_temp_path $fastcgiTemp;
    uwsgi_temp_path $uwsgiTemp;
    scgi_temp_path $scgiTemp;

    server {
        listen 127.0.0.1:$Port;
        server_name localhost;
        add_header X-Nexus-Nginx-Lab true always;

        location / {
            root $distPath;
            try_files `$uri `$uri/ /index.html;
        }
    }
}
"@
    Write-Utf8Text $nginxConfigPath ($nginxConfig + "`n")

    $wrapperConfig = @"
<service>
  <id>$ServiceName</id>
  <name>Nexus Nginx Local Lab</name>
  <description>Isolated Nexus Nginx service-account and ACL experiment.</description>
  <executable>$powerShell7Path</executable>
  <arguments>-NoLogo -NoProfile -NonInteractive -File &quot;$supervisorPath&quot;</arguments>
  <workingdirectory>$labRoot</workingdirectory>
  <startmode>Manual</startmode>
  <stoptimeout>30 sec</stoptimeout>
  <hidewindow>true</hidewindow>
  <logpath>$serviceLogsRoot</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>8</keepFiles>
  </log>
  <onfailure action="restart" delay="10 sec" />
  <onfailure action="restart" delay="30 sec" />
  <onfailure action="none" />
  <resetfailure>1 hour</resetfailure>
</service>
"@
    Write-Utf8Text $wrapperConfigPath ($wrapperConfig + "`n")

    foreach ($readOnlyRoot in @($serviceRoot, $nginxRoot, $configRoot, $distRoot)) {
        Get-ChildItem -LiteralPath $readOnlyRoot -File -Recurse |
            ForEach-Object { $_.IsReadOnly = $true }
    }

    $configTestStdout = Join-Path $evidenceRoot 'nginx-config-test.stdout.txt'
    $configTestStderr = Join-Path $evidenceRoot 'nginx-config-test.stderr.txt'
    $configTestProcess = Start-Process -FilePath $nginxPath `
        -ArgumentList @('-t', '-p', "$prefix/", '-c', $configPath) `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $configTestStdout `
        -RedirectStandardError $configTestStderr
    if ($configTestProcess.ExitCode -ne 0) {
        throw "Nginx configuration test failed with exit code $($configTestProcess.ExitCode)."
    }

    & $wrapperPath install | Out-File -LiteralPath `
        (Join-Path $evidenceRoot 'winsw-install.txt') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "WinSW install failed with exit code $LASTEXITCODE." }
    $serviceInstalled = $true

    $scOutput = & sc.exe config $ServiceName obj= $serviceIdentityName 2>&1
    $scOutput | Out-File -LiteralPath (Join-Path $evidenceRoot 'service-account.txt') `
        -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to assign virtual service account: $($scOutput -join ' ')"
    }

    $serviceSid = ([Security.Principal.NTAccount]::new($serviceIdentityName)).Translate(
        [Security.Principal.SecurityIdentifier])
    Set-RestrictedLabAcl $serviceSid
    Assert-NoServiceWriteAccess $distRoot $serviceSid
    & icacls.exe $labRoot /t /c | Out-File -LiteralPath `
        (Join-Path $evidenceRoot 'acl-after.txt') -Encoding utf8
    & sc.exe qc $ServiceName | Out-File -LiteralPath `
        (Join-Path $evidenceRoot 'service-query.txt') -Encoding utf8
    & sc.exe qfailure $ServiceName | Out-File -LiteralPath `
        (Join-Path $evidenceRoot 'service-recovery.txt') -Encoding utf8

    $serviceInfo = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    if ($serviceInfo.StartMode -ne 'Manual' -or
        $serviceInfo.StartName -ne $serviceIdentityName) {
        throw "Unexpected service configuration: start=$($serviceInfo.StartMode) account=$($serviceInfo.StartName)"
    }

    Start-Service -Name $ServiceName
    Wait-ServiceState 'Running' 20
    $firstBytes = Wait-Health 30 $expectedIndexHash
    $first = Wait-NginxTopology 15
    Assert-OnlyLoopbackListener
    if ($first.Owner -notmatch "(?i)^NT SERVICE\\$([regex]::Escape($ServiceName))$") {
        throw "Nginx runs as the wrong identity: $($first.Owner)"
    }
    Write-Output "START: service=Running masterPid=$($first.MasterPid) workerPid=$($first.WorkerPid) owner=$($first.Owner) bytes=$firstBytes"

    Stop-Service -Name $ServiceName
    Wait-ServiceState 'Stopped' 40
    Start-Sleep -Seconds 5
    if ((Get-Service -Name $ServiceName).Status -ne 'Stopped' -or
        (Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue) -or
        @(Get-LabNginxProcesses).Count -ne 0) {
        throw 'Normal stop triggered an unexpected restart or left Nginx running.'
    }
    Write-Output 'STOP: deliberate service stop remained stopped; Nginx processes and listener exited.'

    Start-Service -Name $ServiceName
    Wait-ServiceState 'Running' 20
    [void](Wait-Health 30 $expectedIndexHash)
    $beforeManualRestart = Wait-NginxTopology 15
    Restart-Service -Name $ServiceName
    Wait-ServiceState 'Running' 40
    $afterManualRestart = Wait-NginxTopology 40 $beforeManualRestart.MasterPid
    [void](Wait-Health 30 $expectedIndexHash)
    Assert-OnlyLoopbackListener
    Write-Output "MANUAL-RESTART: oldMasterPid=$($beforeManualRestart.MasterPid) newMasterPid=$($afterManualRestart.MasterPid) health=200"

    $oldProcessIds = @($afterManualRestart.MasterPid, $afterManualRestart.WorkerPid)
    $crashedMasterPid = $afterManualRestart.MasterPid
    Stop-Process -Id $crashedMasterPid -Force
    $afterRecovery = Wait-NginxTopology 60 $crashedMasterPid
    Wait-ServiceState 'Running' 20
    [void](Wait-Health 30 $expectedIndexHash)
    $oldDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $oldRemaining = @(Get-LabNginxProcesses | Where-Object {
            $_.ProcessId -in $oldProcessIds
        })
        if ($oldRemaining.Count -eq 0) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $oldDeadline)
    if ($oldRemaining.Count -ne 0) {
        throw 'Abnormal recovery left an old Nginx process running.'
    }
    $auditLines = @(Get-Content -LiteralPath $cleanupAuditPath)
    $oldWorkerAuditPattern = "action=killed pids=(?:[0-9]+,)*$($afterManualRestart.WorkerPid)(?:,[0-9]+)*$"
    if (-not ($auditLines | Where-Object { $_ -match $oldWorkerAuditPattern })) {
        throw "Pre-start cleanup audit did not record old worker PID $($afterManualRestart.WorkerPid)."
    }
    Write-Output "FAILURE-RECOVERY: killedMasterPid=$crashedMasterPid cleanedWorkerPid=$($afterManualRestart.WorkerPid) recoveredMasterPid=$($afterRecovery.MasterPid) health=200"

    Stop-Service -Name $ServiceName
    Wait-ServiceState 'Stopped' 40
    Start-Sleep -Seconds 5
    if ((Get-Service -Name $ServiceName).Status -ne 'Stopped' -or
        @(Get-LabNginxProcesses).Count -ne 0 -or
        (Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)) {
        throw 'Final deliberate stop did not cleanly stop Nginx.'
    }

    $finalIndexHash = (Get-FileHash -LiteralPath $indexPath -Algorithm SHA256).Hash
    if ($finalIndexHash -ne $expectedIndexHash) {
        throw 'The service modified the isolated dist index.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $nginxLogsRoot 'access.log')) -or
        (Get-Item -LiteralPath (Join-Path $nginxLogsRoot 'access.log')).Length -eq 0) {
        throw 'Nginx did not write its access log in the writable log directory.'
    }
    $serviceErrorLogPath = Join-Path $serviceLogsRoot "$ServiceName.err.log"
    if ((Test-Path -LiteralPath $serviceErrorLogPath) -and
        (Get-Item -LiteralPath $serviceErrorLogPath).Length -ne 0) {
        throw 'The Nginx supervisor wrote unexpected service stderr output.'
    }
    Assert-NoServiceWriteAccess $distRoot $serviceSid

    $result = @"
PASS
service=$ServiceName
account=$serviceIdentityName
operator=$operator
startMode=Manual
bind=127.0.0.1:$Port
servedExactDistIndex=true
normalStopStayedStopped=true
manualRestartChangedMasterPid=true
abnormalExitRecovered=true
recoveryCleanupKilledOldWorker=true
finalStatus=Stopped
distReadOnlyForService=true
nginxLogsWritable=true
serviceStderrEmpty=true
"@
    Write-Utf8Text (Join-Path $evidenceRoot 'result.txt') ($result + "`n")
    Write-Output "FINAL: service=Stopped startMode=Manual port${Port}Listeners=0"
    Write-Output "PASS: Nginx virtual account, restricted recovery cleanup, dist read-only ACL, writable logs, stop, restart, and abnormal recovery succeeded. Evidence=$evidenceRoot"
} catch {
    if ($serviceInstalled) {
        try { Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue } catch {}
    }
    throw
}
