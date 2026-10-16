param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'

function Test-Listener([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Listener([int]$Port, $Process) {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($Process.HasExited) { throw "Process exited before port $Port opened." }
        if (Test-Listener $Port) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Port $Port did not open."
}

if (!(Test-Path -LiteralPath $NginxExe -PathType Leaf)) { throw 'Nginx executable not found.' }
if (!(Test-Path -LiteralPath $JavaExe -PathType Leaf)) { throw 'Java executable not found.' }
foreach ($port in @(18080, 18081)) {
    if (Test-Listener $port) { throw "Port $port is already in use; no process was changed." }
}

$runtimePath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime'))
$logPath = Join-Path $runtimePath 'logs'
New-Item -ItemType Directory -Path $logPath -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'temp') -Force | Out-Null
foreach ($fileName in @('access.log', 'error.log', 'nginx.pid')) {
    $path = Join-Path $logPath $fileName
    if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
}
foreach ($fileName in @('java-output.log', 'java-error.log')) {
    $path = Join-Path $runtimePath $fileName
    if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
}

$nginxPrefix = $runtimePath.Replace('\', '/') + '/'
$nginxConfig = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'nginx.conf')).Replace('\', '/')
$serverSource = Join-Path $PSScriptRoot 'NginxRateLimitProbe.java'
$clientSource = Join-Path $PSScriptRoot 'NginxRateLimitClient.java'
$javaProcess = $null
$nginxProcess = $null

try {
    & $NginxExe -p $nginxPrefix -c $nginxConfig -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed.' }

    $javaProcess = Start-Process -FilePath $JavaExe `
        -ArgumentList @('--add-modules', 'jdk.httpserver,java.desktop', ('"' + $serverSource + '"')) `
        -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimePath 'java-output.log') `
        -RedirectStandardError (Join-Path $runtimePath 'java-error.log')
    Wait-Listener 18081 $javaProcess

    $nginxProcess = Start-Process -FilePath $NginxExe `
        -ArgumentList @('-p', ('"' + $nginxPrefix + '"'), '-c', ('"' + $nginxConfig + '"')) `
        -WorkingDirectory $runtimePath -WindowStyle Hidden -PassThru
    Wait-Listener 18080 $nginxProcess

    & $JavaExe $clientSource
    if ($LASTEXITCODE -ne 0) { throw 'HTTP client assertions failed.' }

    Start-Sleep -Milliseconds 200
    $accessLog = Join-Path $logPath 'access.log'
    $lines = @(Get-Content -LiteralPath $accessLog)
    $status200 = @($lines | Where-Object { $_ -match ' status=200 ' }).Count
    $status401 = @($lines | Where-Object { $_ -match ' status=401 ' }).Count
    $status429 = @($lines | Where-Object { $_ -match ' status=429 ' }).Count
    $slow429 = @($lines | Where-Object {
        if ($_ -match ' status=429 .*requestTime=([0-9.]+)') {
            return [double]$Matches[1] -ge 0.9
        }
        return $false
    }).Count
    if ($lines.Count -ne 15) { throw "Expected 15 Nginx access lines, got $($lines.Count)." }
    if ($status200 -ne 3 -or $status401 -ne 1 -or $status429 -ne 11) {
        throw "Unexpected Nginx statuses: 200=$status200 401=$status401 429=$status429"
    }
    if ($slow429 -ne 10) { throw "Expected 10 delayed 429 log lines, got $slow429." }
    if (($lines -join "`n") -match 'experiment-app-a|Authorization') {
        throw 'Access log leaked the experiment credential or Authorization header name.'
    }
    if (@($lines | Where-Object { $_ -notmatch 'status=([0-9]+).*upstreamStatus=\1' }).Count -ne 0) {
        throw 'Nginx status differs from upstream status.'
    }

    Write-Output "Nginx log: total=15, 200=$status200, 401=$status401, 429=$status429, delayed429=$slow429"
    Write-Output 'Representative access log lines:'
    $lines | Where-Object { $_ -match ' status=(200|401) ' } | Select-Object -First 2
    $lines | Where-Object { $_ -match ' status=429 ' } | Sort-Object { if ($_ -match 'requestTime=([0-9.]+)') { [double]$Matches[1] } else { 99 } } | Select-Object -First 1
    $lines | Where-Object { $_ -match ' status=429 ' } | Sort-Object { if ($_ -match 'requestTime=([0-9.]+)') { [double]$Matches[1] } else { 0 } } -Descending | Select-Object -First 1
} finally {
    if ($null -ne $nginxProcess -and !$nginxProcess.HasExited) {
        & $NginxExe -p $nginxPrefix -c $nginxConfig -s quit
        if (!$nginxProcess.WaitForExit(5000)) {
            $nginxProcess.Kill()
            $nginxProcess.WaitForExit(5000) | Out-Null
        }
    }
    if ($null -ne $javaProcess -and !$javaProcess.HasExited) {
        $javaProcess.Kill()
        $javaProcess.WaitForExit(5000) | Out-Null
    }
}
