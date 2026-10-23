param(
    [string]$DeploymentRoot = 'C:\NexusDeploy'
)

$ErrorActionPreference = 'Stop'
$version = '7.6.6'
$msiName = "PowerShell-$version-win-x64.msi"
$downloadUri = "https://github.com/PowerShell/PowerShell/releases/download/v$version/$msiName"
$expectedMsiHash = '958838FF55091E1C8705D89EFED0CC7E8245A3A6EF6C0CCFAE20015227108AD8'
$expectedExeHash = 'BFB46AF89433268872DDB43D1CA7A3F433452EE91ED356A9786940F90118E285'
$stagingRoot = Join-Path ([IO.Path]::GetFullPath($DeploymentRoot)) `
    'staging\prerequisites'
$msiPath = Join-Path $stagingRoot $msiName
$powerShellPath = 'C:\Program Files\PowerShell\7\pwsh.exe'
$evidenceRoot = Join-Path ([IO.Path]::GetFullPath($DeploymentRoot)) `
    'evidence\windows-nginx-service'

function Assert-Administrator {
    $principal = [Security.Principal.WindowsPrincipal]::new(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'PowerShell installation must run in an elevated Administrator session.'
    }
}

Assert-Administrator
if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'The pinned package is x64, but this operating system is not x64.'
}

New-Item -ItemType Directory -Path $stagingRoot, $evidenceRoot -Force |
    Out-Null

if (Test-Path -LiteralPath $msiPath -PathType Leaf) {
    $existingHash = (Get-FileHash -LiteralPath $msiPath -Algorithm SHA256).Hash
    if ($existingHash -ne $expectedMsiHash) {
        throw "Existing MSI has an unexpected SHA-256 and was not overwritten: $msiPath"
    }
} else {
    Invoke-WebRequest -UseBasicParsing -Uri $downloadUri -OutFile $msiPath
}

$actualMsiHash = (Get-FileHash -LiteralPath $msiPath -Algorithm SHA256).Hash
if ($actualMsiHash -ne $expectedMsiHash) {
    throw "PowerShell MSI SHA-256 mismatch. Expected $expectedMsiHash, actual $actualMsiHash."
}

$signature = Get-AuthenticodeSignature -LiteralPath $msiPath
if ($signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
    $null -eq $signature.SignerCertificate -or
    $signature.SignerCertificate.Subject -notmatch '(?i)O=Microsoft Corporation') {
    throw "PowerShell MSI Authenticode validation failed: status=$($signature.Status) subject=$($signature.SignerCertificate.Subject)"
}

$arguments = @(
    '/i', "`"$msiPath`"", '/qn', '/norestart',
    'USE_MU=0', 'ENABLE_MU=0', 'ADD_PATH=1'
)
$process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" `
    -ArgumentList $arguments -Wait -PassThru
if ($process.ExitCode -notin @(0, 3010)) {
    throw "PowerShell MSI installation failed with exit code $($process.ExitCode)."
}

if (-not (Test-Path -LiteralPath $powerShellPath -PathType Leaf)) {
    throw "PowerShell executable was not installed at the expected path: $powerShellPath"
}
$actualExeHash = (Get-FileHash -LiteralPath $powerShellPath -Algorithm SHA256).Hash
if ($actualExeHash -ne $expectedExeHash) {
    throw "Installed pwsh.exe SHA-256 mismatch. Expected $expectedExeHash, actual $actualExeHash."
}
$installedVersion = & $powerShellPath -NoLogo -NoProfile -Command `
    '$PSVersionTable.PSVersion.ToString()'
if ($installedVersion -ne $version) {
    throw "Expected PowerShell $version, actual $installedVersion."
}

$result = [ordered]@{
    installedAt = [DateTimeOffset]::Now.ToString('o')
    version = $installedVersion
    msiSha256 = $actualMsiHash
    exeSha256 = $actualExeHash
    signerSubject = $signature.SignerCertificate.Subject
    signerThumbprint = $signature.SignerCertificate.Thumbprint
    installerExitCode = $process.ExitCode
    rebootRequired = $process.ExitCode -eq 3010
    microsoftUpdateEnabled = $false
}
$result | ConvertTo-Json | Set-Content -LiteralPath `
    (Join-Path $evidenceRoot 'powershell-install-result.json') -Encoding utf8

Write-Output "PASS: PowerShell $installedVersion installed from the pinned Microsoft-signed MSI."
Write-Output "REBOOT_REQUIRED=$($process.ExitCode -eq 3010)"
