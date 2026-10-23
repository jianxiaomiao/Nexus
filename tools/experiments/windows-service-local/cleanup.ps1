param(
    [string]$ServiceName = 'NexusJavaLab',
    [ValidateRange(1024, 65535)]
    [int]$Port = 18082
)

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Cleanup must run in an elevated Administrator PowerShell.'
}

$experimentRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$runtimeRoot = Join-Path $experimentRoot 'runtime'
$labRoot = Join-Path $runtimeRoot 'lab'
$wrapperPath = Join-Path $labRoot "service\$ServiceName.exe"
$expectedJarPath = Join-Path $labRoot 'release\nexus-server.jar'

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

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
    -ErrorAction SilentlyContinue)
foreach ($listener in $listeners) {
    $process = Get-CimInstance Win32_Process `
        -Filter "ProcessId=$($listener.OwningProcess)"
    if ($listener.LocalAddress -ne '127.0.0.1' -or
        $process.Name -ne 'java.exe' -or
        $process.CommandLine -notlike "*$expectedJarPath*") {
        throw "Refusing to stop unexpected process $($process.ProcessId) on port $Port."
    }
    Stop-Process -Id $process.ProcessId -Force
    $processObject = Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
    if ($null -ne $processObject) {
        $processObject.WaitForExit(10000)
    }
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue) {
    throw "Cleanup failed: port $Port still has a listener."
}

if (Test-Path -LiteralPath $labRoot) {
    $fullLabRoot = [IO.Path]::GetFullPath($labRoot)
    $expectedLabRoot = [IO.Path]::GetFullPath(
        'D:\Nexus\tools\experiments\windows-service-local\runtime\lab')
    if ($fullLabRoot -ne $expectedLabRoot) {
        throw "Refusing to delete unexpected lab path: $fullLabRoot"
    }
    Remove-Item -LiteralPath $fullLabRoot -Recurse -Force
}

Write-Output 'CLEANUP: NexusJavaLab service and lab directory removed; pinned WinSW download retained.'
