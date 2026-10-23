param(
    [string]$ServiceName = 'NexusNginxLab',
    [ValidateRange(1024, 65535)]
    [int]$Port = 18084
)

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Cleanup must run in an elevated Administrator PowerShell.'
}

$experimentRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$labRoot = Join-Path $experimentRoot 'runtime\lab'
$wrapperPath = Join-Path $labRoot "service\$ServiceName.exe"
$expectedNginxPath = Join-Path $labRoot 'nginx\nginx.exe'

$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($null -ne $service) {
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceName -Force
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(40))
    }
    if (-not (Test-Path -LiteralPath $wrapperPath -PathType Leaf)) {
        throw "Refusing to remove the service because its expected wrapper is missing: $wrapperPath"
    }
    & $wrapperPath uninstall
    if ($LASTEXITCODE -ne 0) {
        throw "WinSW uninstall failed with exit code $LASTEXITCODE."
    }
}

$labNginxProcesses = @(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" `
    -ErrorAction SilentlyContinue | Where-Object {
        $_.ExecutablePath -eq $expectedNginxPath -or
        $_.CommandLine -like "*$labRoot*"
    })
foreach ($process in $labNginxProcesses) {
    Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
}

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
    -ErrorAction SilentlyContinue)
if ($listeners.Count -ne 0) {
    throw "Cleanup failed: port $Port still has a listener."
}

if (Test-Path -LiteralPath $labRoot) {
    $fullLabRoot = [IO.Path]::GetFullPath($labRoot)
    $expectedLabRoot = [IO.Path]::GetFullPath(
        'D:\Nexus\tools\experiments\windows-nginx-service-local\runtime\lab')
    if ($fullLabRoot -ne $expectedLabRoot) {
        throw "Refusing to delete unexpected lab path: $fullLabRoot"
    }
    Remove-Item -LiteralPath $fullLabRoot -Recurse -Force
}

Write-Output 'CLEANUP: NexusNginxLab service and isolated lab directory removed.'
