param(
    [string]$ServiceName = 'NexusNginx',
    [string]$DeploymentRoot = 'C:\NexusDeploy'
)

$ErrorActionPreference = 'Stop'
$expectedInstalledSupervisorHash = '2066278773098BB7159D5A535ABBA5732FE53B432BDE853B7ECB54F3D1573A9E'
$deploymentRoot = [IO.Path]::GetFullPath($DeploymentRoot)
$sourceSupervisorPath = Join-Path ([IO.Path]::GetFullPath($PSScriptRoot)) `
    'nginx-supervisor.ps1'
$installedSupervisorPath = Join-Path $deploymentRoot `
    'services\nginx\nginx-supervisor.ps1'
$nginxPath = Join-Path $deploymentRoot `
    'shared\nginx\nginx-1.31.6\nginx.exe'
$nginxRoot = Split-Path $nginxPath -Parent
$configPath = Join-Path $deploymentRoot `
    'config\nginx\legacy-library-service.conf'
$serviceLogsRoot = Join-Path $deploymentRoot 'logs\service\nginx'

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Repair must run in an elevated Administrator PowerShell 7 session.'
    }
}

Assert-Administrator
$service = Get-Service -Name $ServiceName -ErrorAction Stop
if ($service.Status -ne 'Stopped') {
    throw "Service $ServiceName must remain stopped during the repair."
}
foreach ($requiredFile in @($sourceSupervisorPath, $installedSupervisorPath,
        $nginxPath, $configPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}

$installedHash = (Get-FileHash -LiteralPath $installedSupervisorPath `
    -Algorithm SHA256).Hash
if ($installedHash -ne $expectedInstalledSupervisorHash) {
    throw "Installed supervisor is not the expected pre-repair version: $installedHash"
}

$installedFile = Get-Item -LiteralPath $installedSupervisorPath
$installedFile.IsReadOnly = $false
try {
    Copy-Item -LiteralPath $sourceSupervisorPath `
        -Destination $installedSupervisorPath -Force
} finally {
    (Get-Item -LiteralPath $installedSupervisorPath).IsReadOnly = $true
}

$sourceHash = (Get-FileHash -LiteralPath $sourceSupervisorPath `
    -Algorithm SHA256).Hash
$repairedHash = (Get-FileHash -LiteralPath $installedSupervisorPath `
    -Algorithm SHA256).Hash
if ($repairedHash -ne $sourceHash) {
    throw 'Installed supervisor does not match the repaired source file.'
}

$serviceSid = ([Security.Principal.NTAccount]::new(
    "NT SERVICE\$ServiceName")).Translate(
    [Security.Principal.SecurityIdentifier])
$writeMask = [Security.AccessControl.FileSystemRights]::Write
$serviceReadWithoutWrite = $false
foreach ($rule in (Get-Acl -LiteralPath $installedSupervisorPath).Access) {
    $ruleSid = $rule.IdentityReference.Translate(
        [Security.Principal.SecurityIdentifier])
    if ($ruleSid -eq $serviceSid -and
        $rule.AccessControlType -eq
            [Security.AccessControl.AccessControlType]::Allow) {
        if (($rule.FileSystemRights -band $writeMask) -ne 0) {
            throw 'Repair unexpectedly gave the service account write access to its supervisor.'
        }
        $serviceReadWithoutWrite = $true
    }
}
if (-not $serviceReadWithoutWrite) {
    throw 'The repaired supervisor lacks the expected service read rule.'
}

$configTestErrorPath = Join-Path $serviceLogsRoot `
    'nginx-config-test-error.log'
$process = Start-Process -FilePath $nginxPath -ArgumentList @(
    '-t', '-p', ($nginxRoot.Replace('\', '/') + '/'),
    '-c', $configPath.Replace('\', '/'),
    '-e', $configTestErrorPath.Replace('\', '/')) `
    -NoNewWindow -Wait -PassThru
if ($process.ExitCode -ne 0) {
    throw "Nginx configuration test failed with exit code $($process.ExitCode)."
}

Write-Output "REPAIRED: supervisorSha256=$repairedHash service=Stopped"
Write-Output 'PASS: Nginx bootstrap diagnostics now use the dedicated writable service-log directory.'
