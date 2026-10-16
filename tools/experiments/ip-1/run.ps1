param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe',
    [string]$NginxConfig = (Join-Path $PSScriptRoot 'nginx.conf'),
    [string]$RuntimeDirectory = (Join-Path $PSScriptRoot 'runtime'),
    [scriptblock]$ObservationScript = $null,
    [int[]]$AdditionalNginxPorts = @()
)

$ErrorActionPreference = 'Stop'

function Test-Listener([string]$Address, [int]$Port) {
    $probeClient = [System.Net.Sockets.TcpClient]::new()
    try {
        $probeClient.Connect($Address, $Port)
        return $true
    } catch {
        return $false
    } finally {
        $probeClient.Dispose()
    }
}

function Wait-Listener([string]$Address, [int]$Port, $Process) {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($Process.HasExited) { throw "Experiment process exited; inspect runtime logs." }
        if (Test-Listener $Address $Port) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Experiment listener $Address port $Port did not start."
}

if (!(Test-Path -LiteralPath $NginxExe -PathType Leaf)) { throw 'Nginx executable not found.' }
if (!(Test-Path -LiteralPath $JavaExe -PathType Leaf)) { throw 'Java executable not found; supply -JavaExe with the actual JDK executable.' }
foreach ($probeAddress in @('127.0.0.1', '::1')) {
    foreach ($probePort in (@(18080, 18081) + $AdditionalNginxPorts)) {
        if (Test-Listener $probeAddress $probePort) {
            throw "Port $probePort on $probeAddress is already in use; no process was changed."
        }
    }
}

$runtimePath = [System.IO.Path]::GetFullPath($RuntimeDirectory)
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'logs') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'temp') -Force | Out-Null
$nginxPrefix = $runtimePath.Replace('\', '/') + '/'
$experimentNginxConfig = [System.IO.Path]::GetFullPath($NginxConfig).Replace('\', '/')
$javaSource = Join-Path $PSScriptRoot 'PeerIpProbe.java'
$javaProcess = $null
$nginxProcess = $null

try {
    & $NginxExe -p $nginxPrefix -c $experimentNginxConfig -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx experiment configuration check failed.' }

    $javaProcess = Start-Process -FilePath $JavaExe `
        -ArgumentList @('--add-modules', 'jdk.httpserver', ('"' + $javaSource + '"')) `
        -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimePath 'java-output.log') `
        -RedirectStandardError (Join-Path $runtimePath 'java-error.log')
    Wait-Listener '127.0.0.1' 18081 $javaProcess

    Write-Output 'Direct Java observation (control request):'
    & curl.exe --silent --show-error --fail --max-time 5 --noproxy '*' 'http://127.0.0.1:18081/peer'
    if ($LASTEXITCODE -ne 0) { throw 'Direct Java observation failed.' }

    $nginxProcess = Start-Process -FilePath $NginxExe `
        -ArgumentList @('-p', ('"' + $nginxPrefix + '"'), '-c', ('"' + $experimentNginxConfig + '"')) `
        -WorkingDirectory $runtimePath -WindowStyle Hidden -PassThru
    Wait-Listener '127.0.0.1' 18080 $nginxProcess
    Wait-Listener '::1' 18080 $nginxProcess
    foreach ($probePort in $AdditionalNginxPorts) {
        Wait-Listener '127.0.0.1' $probePort $nginxProcess
    }

    if ($null -ne $ObservationScript) {
        & $ObservationScript | Tee-Object -FilePath (Join-Path $runtimePath 'observations.log')
    } else {
        Write-Output 'IPv4 client -> Nginx -> Java:'
        & curl.exe --silent --show-error --fail --max-time 5 --noproxy '*' 'http://127.0.0.1:18080/peer'
        if ($LASTEXITCODE -ne 0) { throw 'IPv4 observation failed.' }

        Write-Output 'IPv6 client -> Nginx -> IPv4 Java:'
        & curl.exe --silent --show-error --fail --max-time 5 --noproxy '*' 'http://[::1]:18080/peer'
        if ($LASTEXITCODE -ne 0) { throw 'IPv6 observation failed.' }
    }

    Write-Output 'Nginx access log (latest experiment requests):'
    Get-Content -LiteralPath (Join-Path $runtimePath 'logs/access.log') -Tail 2
} finally {
    if ($null -ne $nginxProcess -and !$nginxProcess.HasExited) {
        & $NginxExe -p $nginxPrefix -c $experimentNginxConfig -s quit
        if (!$nginxProcess.WaitForExit(5000)) {
            $nginxProcess.Kill($true)
            $nginxProcess.WaitForExit(5000) | Out-Null
        }
    }
    if ($null -ne $javaProcess -and !$javaProcess.HasExited) {
        $javaProcess.Kill($true)
        $javaProcess.WaitForExit(5000) | Out-Null
    }
}
