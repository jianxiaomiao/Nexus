param(
    [string]$ServiceName = 'NexusNginx',
    [string]$DeploymentRoot = 'C:\NexusDeploy',
    [string]$PowerShellPath = 'C:\Program Files\PowerShell\7\pwsh.exe',
    [string]$WinSwSourcePath = 'C:\NexusDeploy\staging\windows-nginx-service\WinSW-x64-2.12.0.exe'
)

$ErrorActionPreference = 'Stop'
$expectedWinSwHash = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$expectedNginxHash = '2429269C8EA20CE6C5040BB4EB5FD78A28003DFA272F66D146338320353C5F62'
$expectedPowerShellVersion = [Version]'7.6.6'
$expectedPowerShellHash = 'BFB46AF89433268872DDB43D1CA7A3F433452EE91ED356A9786940F90118E285'

$packageRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$deploymentRoot = [IO.Path]::GetFullPath($DeploymentRoot)
$nginxRoot = Join-Path $deploymentRoot 'shared\nginx\nginx-1.31.6'
$nginxPath = Join-Path $nginxRoot 'nginx.exe'
$configRoot = Join-Path $deploymentRoot 'config\nginx'
$configPath = Join-Path $configRoot 'legacy-library-service.conf'
$distRoot = Join-Path $deploymentRoot 'candidates\legacy-library\V2026.10.21.1__nginx-relative-paths\dist'
$serviceRoot = Join-Path $deploymentRoot 'services\nginx'
$wrapperPath = Join-Path $serviceRoot "$ServiceName.exe"
$wrapperConfigPath = Join-Path $serviceRoot "$ServiceName.xml"
$supervisorPath = Join-Path $serviceRoot 'nginx-supervisor.ps1'
$nginxLogsRoot = Join-Path $deploymentRoot 'logs\nginx'
$serviceLogsRoot = Join-Path $deploymentRoot 'logs\service\nginx'
$runRoot = Join-Path $deploymentRoot 'run\nginx'
$tempRoot = Join-Path $deploymentRoot 'temp\nginx'
$evidenceRoot = Join-Path $deploymentRoot 'evidence\windows-nginx-service'
$cleanupAuditPath = Join-Path $serviceLogsRoot 'recovery-cleanup.log'
$serviceIdentityName = "NT SERVICE\$ServiceName"
$sourceSupervisorPath = Join-Path $packageRoot 'nginx-supervisor.ps1'
$sourceConfigPath = Join-Path $packageRoot 'nginx-loopback.conf'

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Installation must run in an elevated Administrator PowerShell 7 session.'
    }
}

function Assert-PathUnderRoot([string]$Path, [string]$Root) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing a path outside the deployment root: $fullPath"
    }
}

function Assert-FileHash([string]$Path, [string]$ExpectedHash, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedHash) {
        throw "$Label SHA-256 mismatch. Expected $ExpectedHash, actual $actualHash."
    }
}

function Add-FileRule(
    [Security.AccessControl.FileSystemSecurity]$Acl,
    [Security.Principal.IdentityReference]$Identity,
    [Security.AccessControl.FileSystemRights]$Rights,
    [bool]$Directory) {
    if ($Directory) {
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        $propagation = [Security.AccessControl.PropagationFlags]::None
    } else {
        $inheritance = [Security.AccessControl.InheritanceFlags]::None
        $propagation = [Security.AccessControl.PropagationFlags]::None
    }
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $Identity, $Rights, $inheritance, $propagation,
        [Security.AccessControl.AccessControlType]::Allow)
    [void]$Acl.AddAccessRule($rule)
}

function Set-RestrictedTreeAcl(
    [string]$Path,
    [Security.Principal.SecurityIdentifier]$ServiceSid,
    [Security.AccessControl.FileSystemRights]$ServiceRights) {
    $systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $administratorsSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $items = @((Get-Item -LiteralPath $Path -Force)) +
        @(Get-ChildItem -LiteralPath $Path -Recurse -Force)

    foreach ($item in $items) {
        $isDirectory = $item.PSIsContainer
        if ($isDirectory) {
            $acl = [Security.AccessControl.DirectorySecurity]::new()
        } else {
            $acl = [Security.AccessControl.FileSecurity]::new()
        }
        $acl.SetAccessRuleProtection($true, $false)
        Add-FileRule $acl $systemSid `
            ([Security.AccessControl.FileSystemRights]::FullControl) $isDirectory
        Add-FileRule $acl $administratorsSid `
            ([Security.AccessControl.FileSystemRights]::FullControl) $isDirectory
        Add-FileRule $acl $ServiceSid $ServiceRights $isDirectory
        Set-Acl -LiteralPath $item.FullName -AclObject $acl
    }
}

function ConvertTo-XmlText([string]$Value) {
    return [Security.SecurityElement]::Escape($Value)
}

Assert-Administrator

if ($PSVersionTable.PSEdition -ne 'Core' -or
    $PSVersionTable.PSVersion -ne $expectedPowerShellVersion) {
    throw "Run this script with PowerShell $expectedPowerShellVersion. Current version: $($PSVersionTable.PSVersion)."
}
Assert-FileHash $PowerShellPath $expectedPowerShellHash 'PowerShell 7 executable'
Assert-FileHash $WinSwSourcePath $expectedWinSwHash 'WinSW 2.12.0'
Assert-FileHash $nginxPath $expectedNginxHash 'Nginx 1.31.6'

if (-not (Get-Command Get-MpComputerStatus -ErrorAction SilentlyContinue) -or
    -not (Get-Command Start-MpScan -ErrorAction SilentlyContinue)) {
    throw 'Microsoft Defender cmdlets are unavailable; the unsigned WinSW binary was not accepted.'
}
$defender = Get-MpComputerStatus
if (-not $defender.AntivirusEnabled) {
    throw 'Microsoft Defender Antivirus is disabled; the unsigned WinSW binary was not accepted.'
}
Start-MpScan -ScanType CustomScan -ScanPath $WinSwSourcePath
$matchingThreats = @(Get-MpThreatDetection -ErrorAction SilentlyContinue |
    Where-Object {
        @($_.Resources) | Where-Object {
            $_ -like "*$WinSwSourcePath*"
        }
    })
if ($matchingThreats.Count -ne 0) {
    throw 'Microsoft Defender reported a threat for the pinned WinSW binary.'
}

foreach ($requiredFile in @($sourceSupervisorPath, $sourceConfigPath,
        (Join-Path $distRoot 'index.html'))) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}

foreach ($controlledPath in @($nginxRoot, $configRoot, $distRoot, $serviceRoot,
        $nginxLogsRoot, $serviceLogsRoot, $runRoot, $tempRoot, $evidenceRoot)) {
    Assert-PathUnderRoot $controlledPath $deploymentRoot
}

if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    throw "Service $ServiceName already exists; refusing to overwrite it."
}
if (Test-Path -LiteralPath $serviceRoot) {
    throw "Service directory already exists; refusing to overwrite it: $serviceRoot"
}
if (Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue) {
    throw 'Port 18080 already has a listener; stop and inspect it before installation.'
}
if (Get-NetTCPConnection -State Listen -LocalPort 80,443 -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -ne 4 }) {
    # This is evidence only. IIS commonly reports PID 4 through HTTP.sys.
    Write-Warning 'A non-HTTP.sys process is listening on 80 or 443; this installer will not modify it.'
}

$createdService = $false
try {
    New-Item -ItemType Directory -Path $serviceRoot, $configRoot,
        $nginxLogsRoot, $serviceLogsRoot, $runRoot, $tempRoot,
        $evidenceRoot -Force | Out-Null
    foreach ($tempName in @('client-body', 'proxy', 'fastcgi', 'uwsgi', 'scgi')) {
        New-Item -ItemType Directory -Path (Join-Path $tempRoot $tempName) `
            -Force | Out-Null
    }

    Copy-Item -LiteralPath $WinSwSourcePath -Destination $wrapperPath
    Copy-Item -LiteralPath $sourceSupervisorPath -Destination $supervisorPath
    Copy-Item -LiteralPath $sourceConfigPath -Destination $configPath

    $powerShellXml = ConvertTo-XmlText ([IO.Path]::GetFullPath($PowerShellPath))
    $supervisorXml = ConvertTo-XmlText $supervisorPath
    $nginxXml = ConvertTo-XmlText $nginxPath
    $prefixXml = ConvertTo-XmlText $nginxRoot
    $configXml = ConvertTo-XmlText $configPath
    $ownerXml = ConvertTo-XmlText $serviceIdentityName
    $auditXml = ConvertTo-XmlText $cleanupAuditPath
    $serviceRootXml = ConvertTo-XmlText $serviceRoot
    $serviceLogsXml = ConvertTo-XmlText $serviceLogsRoot
    $arguments = @(
        '-NoLogo', '-NoProfile', '-NonInteractive',
        '-File', "&quot;$supervisorXml&quot;",
        '-NginxPath', "&quot;$nginxXml&quot;",
        '-PrefixPath', "&quot;$prefixXml&quot;",
        '-ConfigPath', "&quot;$configXml&quot;",
        '-ExpectedOwner', "&quot;$ownerXml&quot;",
        '-AuditPath', "&quot;$auditXml&quot;"
    ) -join ' '

    $wrapperConfig = @"
<service>
  <id>$ServiceName</id>
  <name>Nexus Nginx</name>
  <description>Nexus edge Nginx, currently restricted to the loopback migration port.</description>
  <executable>$powerShellXml</executable>
  <arguments>$arguments</arguments>
  <workingdirectory>$serviceRootXml</workingdirectory>
  <startmode>Manual</startmode>
  <stoptimeout>140 sec</stoptimeout>
  <hidewindow>true</hidewindow>
  <logpath>$serviceLogsXml</logpath>
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
    [IO.File]::WriteAllText($wrapperConfigPath, $wrapperConfig + "`n",
        [Text.UTF8Encoding]::new($false))

    $configTest = Start-Process -FilePath $nginxPath -ArgumentList @(
        '-t', '-p', ($nginxRoot.Replace('\', '/') + '/'),
        '-c', $configPath.Replace('\', '/'),
        '-e', (Join-Path $serviceLogsRoot 'nginx-config-test-error.log').Replace('\', '/')) `
        -NoNewWindow -Wait -PassThru
    if ($configTest.ExitCode -ne 0) {
        throw "Nginx configuration test failed with exit code $($configTest.ExitCode)."
    }

    & $wrapperPath install
    if ($LASTEXITCODE -ne 0) {
        throw "WinSW install failed with exit code $LASTEXITCODE."
    }
    $createdService = $true

    $scOutput = & sc.exe config $ServiceName obj= $serviceIdentityName 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to assign the virtual service account: $($scOutput -join ' ')"
    }

    $serviceSid = ([Security.Principal.NTAccount]::new(
        $serviceIdentityName)).Translate(
        [Security.Principal.SecurityIdentifier])
    $readRights = [Security.AccessControl.FileSystemRights]::ReadAndExecute
    $modifyRights = [Security.AccessControl.FileSystemRights]::Modify

    foreach ($readOnlyRoot in @($nginxRoot, $configRoot, $distRoot, $serviceRoot)) {
        Set-RestrictedTreeAcl $readOnlyRoot $serviceSid $readRights
    }
    foreach ($writableRoot in @($nginxLogsRoot, $serviceLogsRoot,
            $runRoot, $tempRoot)) {
        Set-RestrictedTreeAcl $writableRoot $serviceSid $modifyRights
    }

    Get-ChildItem -LiteralPath $serviceRoot, $configRoot -File -Recurse |
        ForEach-Object { $_.IsReadOnly = $true }

    $service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    if ($service.StartMode -ne 'Manual' -or
        $service.StartName -ne $serviceIdentityName -or
        $service.State -ne 'Stopped') {
        throw "Unexpected service state: mode=$($service.StartMode) account=$($service.StartName) state=$($service.State)"
    }

    $summary = [ordered]@{
        installedAt = [DateTimeOffset]::Now.ToString('o')
        service = $ServiceName
        account = $serviceIdentityName
        startMode = $service.StartMode
        state = $service.State
        bind = '127.0.0.1:18080'
        nginxSha256 = $expectedNginxHash
        winSwSha256 = $expectedWinSwHash
        powerShellVersion = $expectedPowerShellVersion.ToString()
        configSha256 = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
        defenderAntivirusEnabled = [bool]$defender.AntivirusEnabled
        defenderSignatureVersion = $defender.AntivirusSignatureVersion
        winSwDefenderThreatMatches = $matchingThreats.Count
    }
    $summary | ConvertTo-Json | Set-Content -LiteralPath `
        (Join-Path $evidenceRoot 'install-result.json') -Encoding utf8
    & sc.exe qc $ServiceName | Set-Content -LiteralPath `
        (Join-Path $evidenceRoot 'service-query.txt') -Encoding utf8
    & sc.exe qfailure $ServiceName | Set-Content -LiteralPath `
        (Join-Path $evidenceRoot 'service-recovery.txt') -Encoding utf8
    $aclEvidencePath = Join-Path $evidenceRoot 'acl-after.txt'
    Remove-Item -LiteralPath $aclEvidencePath -Force -ErrorAction SilentlyContinue
    foreach ($aclRoot in @($nginxRoot, $configRoot, $distRoot, $serviceRoot,
            $nginxLogsRoot, $serviceLogsRoot, $runRoot, $tempRoot)) {
        & icacls.exe $aclRoot /t /c | Add-Content -LiteralPath `
            $aclEvidencePath -Encoding utf8
        if ($LASTEXITCODE -ne 0) {
            throw "ACL evidence collection failed for $aclRoot."
        }
    }

    Write-Output "INSTALLED: service=$ServiceName account=$serviceIdentityName mode=Manual state=Stopped bind=127.0.0.1:18080"
    Write-Output "NEXT: run accept.ps1 from an elevated PowerShell 7 session."
} catch {
    if ($createdService) {
        try { & $wrapperPath uninstall | Out-Null } catch {}
    }
    throw
}
