param(
    [string]$ServiceName = 'NexusJavaLab',
    [ValidateRange(1024, 65535)]
    [int]$Port = 18082
)

$ErrorActionPreference = 'Stop'
$expectedWinSwHash = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$experimentRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $experimentRoot '../../..'))
$runtimeRoot = Join-Path $experimentRoot 'runtime'
$downloadPath = Join-Path $runtimeRoot 'WinSW-x64-2.12.0.exe'
$labRoot = Join-Path $runtimeRoot 'lab'
$serviceRoot = Join-Path $labRoot 'service'
$releaseRoot = Join-Path $labRoot 'release'
$configRoot = Join-Path $labRoot 'config'
$logsRoot = Join-Path $labRoot 'logs'
$serviceLogsRoot = Join-Path $logsRoot 'service'
$javaLogsRoot = Join-Path $logsRoot 'java'
$tempRoot = Join-Path $labRoot 'temp'
$evidenceRoot = Join-Path $labRoot 'evidence'
$wrapperPath = Join-Path $serviceRoot "$ServiceName.exe"
$wrapperConfigPath = Join-Path $serviceRoot "$ServiceName.xml"
$jarPath = Join-Path $releaseRoot 'nexus-server.jar'
$sourceJarPath = Join-Path $repoRoot 'nexus-server\target\nexus-server-0.0.1-SNAPSHOT.jar'
$sourceLocalConfigPath = Join-Path $repoRoot 'nexus-server\config\application-local.yml'
$labConfigPath = Join-Path $configRoot 'application-local.yml'
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

function ConvertTo-YamlQuoted([string]$Value) {
    return ($Value | ConvertTo-Json -Compress)
}

function Get-YamlScalar([string]$Line, [string]$Name, [bool]$Commented) {
    $prefix = if ($Commented) { '^\s*#\s*' } else { '^\s*' }
    $value = $Line -replace ($prefix + [regex]::Escape($Name) + '\s*:\s*'), ''
    return $value.Trim().Trim('"').Trim("'")
}

function Get-TestDatabaseSettings {
    $lines = Get-Content -LiteralPath $sourceLocalConfigPath
    $urlLine = $lines | Where-Object {
        $_ -match '^\s*#\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
    } | Select-Object -First 1
    $userLine = $lines | Where-Object {
        $_ -match '^\s*#\s*username\s*:'
    } | Select-Object -First 1
    $passwordLine = $lines | Where-Object {
        $_ -match '^\s*#\s*password\s*:'
    } | Select-Object -First 1
    if (-not $urlLine -or -not $userLine -or -not $passwordLine) {
        throw 'The saved dedicated nexus_test settings are incomplete.'
    }
    $url = Get-YamlScalar $urlLine 'url' $true
    $username = Get-YamlScalar $userLine 'username' $true
    $password = Get-YamlScalar $passwordLine 'password' $true
    if ($url -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/nexus_test(?:\?|$)' -or
        $username -ne 'nexus_test_user' -or
        [string]::IsNullOrWhiteSpace($password)) {
        throw 'The selected settings are not the dedicated nexus_test account.'
    }
    return [pscustomobject]@{
        Url = $url
        Username = $username
        Password = $password
    }
}

function New-RandomSecret([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
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

    foreach ($writablePath in @($logsRoot, $tempRoot)) {
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

function Wait-Health([int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -SkipHttpErrorCheck `
                -TimeoutSec 2 -Uri "http://127.0.0.1:$Port/v1/utils/uuid"
            $json = $response.Content | ConvertFrom-Json
            if ($response.StatusCode -eq 401 -and
                $json.code -eq 'INVALID_API_KEY_CREDENTIAL') {
                return
            }
        } catch {}
        Start-Sleep -Milliseconds 300
    }
    throw "Java health boundary did not become ready on 127.0.0.1:$Port."
}

function Get-ListeningJavaProcess {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue | Where-Object {
            $_.LocalAddress -eq '127.0.0.1'
        } | Select-Object -First 1
    if ($null -eq $listener) { return $null }
    $java = Get-CimInstance Win32_Process `
        -Filter "ProcessId=$($listener.OwningProcess)" `
        -ErrorAction SilentlyContinue
    if ($null -eq $java -or $java.Name -ne 'java.exe') { return $null }
    $parent = Get-CimInstance Win32_Process `
        -Filter "ProcessId=$($java.ParentProcessId)" `
        -ErrorAction SilentlyContinue
    $owner = Invoke-CimMethod -InputObject $java -MethodName GetOwner
    return [pscustomobject]@{
        JavaPid = [int]$java.ProcessId
        ParentPid = [int]$java.ParentProcessId
        ParentName = if ($null -eq $parent) { $null } else { $parent.Name }
        Owner = "$($owner.Domain)\$($owner.User)"
    }
}

function Wait-ListeningJavaProcess([int]$Seconds, [int]$DifferentFromPid = 0) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $java = Get-ListeningJavaProcess
        if ($null -ne $java -and
            ($DifferentFromPid -eq 0 -or $java.JavaPid -ne $DifferentFromPid)) {
            return $java
        }
        Start-Sleep -Milliseconds 250
    }
    throw "The expected Java listener did not appear on 127.0.0.1:$Port."
}

function Assert-OnlyLoopbackListener {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1') {
        throw "Port $Port is not exclusively bound to IPv4 loopback."
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
if (-not (Test-Path -LiteralPath $downloadPath -PathType Leaf)) {
    throw "Pinned WinSW download is missing: $downloadPath"
}
if ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash -ne
    $expectedWinSwHash) {
    throw 'Pinned WinSW SHA-256 does not match the recorded value.'
}
if (-not (Test-Path -LiteralPath $sourceJarPath -PathType Leaf)) {
    throw "Nexus JAR is missing: $sourceJarPath"
}

$javaHomeCandidate = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else { $null }
$javaExe = if ($javaHomeCandidate -and
    (Test-Path -LiteralPath $javaHomeCandidate -PathType Leaf)) {
    $javaHomeCandidate
} elseif (Test-Path -LiteralPath 'D:\java21\bin\java.exe' -PathType Leaf) {
    'D:\java21\bin\java.exe'
} else {
    throw 'A direct Java 21 executable is required; Oracle javapath forwarding is not accepted.'
}
$database = Get-TestDatabaseSettings
$operator = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$serviceInstalled = $false

try {
    New-Item -ItemType Directory -Path $serviceRoot, $releaseRoot, $configRoot,
        $serviceLogsRoot, $javaLogsRoot, $tempRoot, $evidenceRoot -Force | Out-Null
    & icacls.exe $labRoot /save (Join-Path $evidenceRoot 'acl-before.txt') /t /c |
        Out-File -LiteralPath (Join-Path $evidenceRoot 'acl-save-output.txt') `
            -Encoding utf8

    Copy-Item -LiteralPath $downloadPath -Destination $wrapperPath
    Copy-Item -LiteralPath $sourceJarPath -Destination $jarPath
    (Get-Item -LiteralPath $jarPath).IsReadOnly = $true

    $jwtSecret = New-RandomSecret 32
    $labConfig = @"
server:
  address: 127.0.0.1
  port: $Port

spring:
  datasource:
    hikari:
      maximum-pool-size: 2
      minimum-idle: 1
    url: $(ConvertTo-YamlQuoted $database.Url)
    username: $(ConvertTo-YamlQuoted $database.Username)
    password: $(ConvertTo-YamlQuoted $database.Password)

nexus:
  auth:
    jwt:
      issuer: nexus
      access-token-ttl: 30m
      secret: $(ConvertTo-YamlQuoted $jwtSecret)
"@
    Write-Utf8Text $labConfigPath ($labConfig + "`n")

    $configUri = $configRoot.Replace('\', '/') + '/'
    $wrapperConfig = @"
<service>
  <id>$ServiceName</id>
  <name>Nexus Java Local Lab</name>
  <description>Isolated Nexus Java service-account and recovery experiment.</description>
  <executable>$javaExe</executable>
  <arguments>-jar &quot;$jarPath&quot; --spring.profiles.active=local --spring.config.additional-location=file:$configUri</arguments>
  <workingdirectory>$labRoot</workingdirectory>
  <startmode>Manual</startmode>
  <stoptimeout>30 sec</stoptimeout>
  <env name="NEXUS_LOG_DIR" value="$javaLogsRoot" />
  <env name="TEMP" value="$tempRoot" />
  <env name="TMP" value="$tempRoot" />
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

    & $wrapperPath install | Out-File -LiteralPath `
        (Join-Path $evidenceRoot 'winsw-install.txt') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "WinSW install failed with exit code $LASTEXITCODE." }
    $serviceInstalled = $true

    # Microsoft documents virtual accounts with obj= only; no password is supplied.
    $scOutput = & sc.exe config $ServiceName obj= $serviceIdentityName 2>&1
    $scOutput | Out-File -LiteralPath (Join-Path $evidenceRoot 'service-account.txt') `
        -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to assign virtual service account: $($scOutput -join ' ')"
    }

    $serviceSid = ([Security.Principal.NTAccount]::new($serviceIdentityName)).Translate(
        [Security.Principal.SecurityIdentifier])
    Set-RestrictedLabAcl $serviceSid
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
    Wait-Health 90
    $first = Wait-ListeningJavaProcess 10
    Assert-OnlyLoopbackListener
    if ($first.Owner -notmatch "(?i)^NT SERVICE\\$([regex]::Escape($ServiceName))$") {
        throw "Java runs as the wrong identity: $($first.Owner)"
    }
    if ($first.ParentName -ne "$ServiceName.exe") {
        throw "The listening Java process is not a direct WinSW child: $($first.ParentName)"
    }
    Write-Output "START: service=Running javaPid=$($first.JavaPid) owner=$($first.Owner) health=401"

    Stop-Service -Name $ServiceName
    Wait-ServiceState 'Stopped' 40
    Start-Sleep -Seconds 12
    if ((Get-Service -Name $ServiceName).Status -ne 'Stopped' -or
        (Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)) {
        throw 'Normal stop triggered an unexpected restart or left the port open.'
    }
    Write-Output 'STOP: deliberate service stop remained stopped; no recovery restart occurred.'

    Start-Service -Name $ServiceName
    Wait-ServiceState 'Running' 20
    Wait-Health 90
    $beforeManualRestart = Wait-ListeningJavaProcess 10
    Restart-Service -Name $ServiceName
    Wait-ServiceState 'Running' 40
    $afterManualRestart = Wait-ListeningJavaProcess 90 $beforeManualRestart.JavaPid
    Wait-Health 90
    Assert-OnlyLoopbackListener
    Write-Output "MANUAL-RESTART: oldJavaPid=$($beforeManualRestart.JavaPid) newJavaPid=$($afterManualRestart.JavaPid) health=401"

    $crashedPid = $afterManualRestart.JavaPid
    Stop-Process -Id $crashedPid -Force
    $afterRecovery = Wait-ListeningJavaProcess 90 $crashedPid
    Wait-ServiceState 'Running' 30
    Wait-Health 90
    Assert-OnlyLoopbackListener
    Write-Output "FAILURE-RECOVERY: killedJavaPid=$crashedPid recoveredJavaPid=$($afterRecovery.JavaPid) health=401"

    Stop-Service -Name $ServiceName
    Wait-ServiceState 'Stopped' 40
    Start-Sleep -Seconds 12
    if ((Get-Service -Name $ServiceName).Status -ne 'Stopped') {
        throw 'Final deliberate stop did not remain stopped.'
    }

    $writableReleaseFiles = @(Get-ChildItem -LiteralPath $releaseRoot -File -Recurse |
        Where-Object { -not $_.IsReadOnly })
    if ($writableReleaseFiles.Count -ne 0) {
        throw 'The lab release contains writable files.'
    }
    $sensitiveHits = @(Get-ChildItem -LiteralPath $logsRoot -File -Recurse |
        Select-String -Pattern '(?i)(authorization:\s*(bearer|apikey)|jwt[-_. ]?(secret|key)\s*[:=]|password\s*[:=])' `
            -ErrorAction SilentlyContinue)
    if ($sensitiveHits.Count -ne 0) {
        throw "Service logs contain $($sensitiveHits.Count) sensitive-pattern hits."
    }

    $result = @"
PASS
service=$ServiceName
account=$serviceIdentityName
operator=$operator
startMode=Manual
bind=127.0.0.1:$Port
normalStopStayedStopped=true
manualRestartChangedJavaPid=true
abnormalExitRecovered=true
finalStatus=Stopped
releaseReadOnly=true
sensitivePatternHits=0
"@
    Write-Utf8Text (Join-Path $evidenceRoot 'result.txt') ($result + "`n")
    Write-Output "FINAL: service=Stopped startMode=Manual port${Port}Listeners=0"
    Write-Output "PASS: Windows virtual account, ACL, normal stop, manual restart, and abnormal recovery succeeded. Evidence=$evidenceRoot"
} catch {
    if ($serviceInstalled) {
        try { Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue } catch {}
    }
    throw
}
