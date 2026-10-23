param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = '',
    [int]$NginxPort = 18080,
    [int]$BackendPort = 18081,
    [string]$ReleaseJar = '',
    [string]$FrontendDist = '',
    [string]$BackendWorkingDirectory = '',
    [string]$PlaywrightBrowsersPath = ''
)

$ErrorActionPreference = 'Stop'
$repoPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$serverPath = Join-Path $repoPath 'nexus-server'
$localConfigPath = Join-Path $serverPath 'config/application-local.yml'
$runId = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff') + "-$PID"
$runtimePath = Join-Path $PSScriptRoot "runtime/$runId"
$runtimeLogsPath = Join-Path $runtimePath 'logs'
$backendOutputPath = Join-Path $runtimePath 'backend-output.log'
$backendErrorPath = Join-Path $runtimePath 'backend-error.log'
$nginxConfigPath = Join-Path $runtimePath 'nginx.conf'
$nginxPrefix = $runtimePath.Replace('\', '/') + '/'

function Get-ListeningSnapshot([int[]]$Ports) {
    return @(
        Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $Ports } |
            ForEach-Object { "$($_.LocalAddress)|$($_.LocalPort)|$($_.OwningProcess)" } |
            Sort-Object
    )
}

function Assert-PortUnused([int]$Port) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        throw "Port $Port is already in use; no process was changed."
    }
}

function Test-LoopbackListener([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-LoopbackListener([int]$Port, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-LoopbackListener $Port) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "Port $Port did not open on 127.0.0.1 within $Seconds seconds."
}

function Test-TcpConnection([string]$Address, [int]$Port, [int]$TimeoutMs = 1500) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($Address, $Port)
        try {
            if (-not $task.Wait($TimeoutMs)) { return $false }
        } catch {
            return $false
        }
        return $client.Connected
    } finally {
        $client.Dispose()
    }
}

function Get-YamlScalar([string]$Line, [string]$Name, [bool]$Commented) {
    $prefix = if ($Commented) { '^\s*#\s*' } else { '^\s*' }
    $value = $Line -replace ($prefix + [regex]::Escape($Name) + '\s*:\s*'), ''
    return $value.Trim().Trim('"').Trim("'")
}

function Get-TestDatabaseSettings {
    if (-not (Test-Path -LiteralPath $localConfigPath -PathType Leaf)) {
        throw "Missing local config: $localConfigPath"
    }

    $lines = Get-Content -LiteralPath $localConfigPath
    $activeUrlLine = $lines | Where-Object {
        $_ -match '^\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
    } | Select-Object -First 1
    $useCommented = $null -eq $activeUrlLine
    $urlLine = if ($useCommented) {
        $lines | Where-Object {
            $_ -match '^\s*#\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
        } | Select-Object -First 1
    } else { $activeUrlLine }
    $settingPrefix = if ($useCommented) { '^\s*#\s*' } else { '^\s*' }
    $userLine = $lines | Where-Object {
        $_ -match ($settingPrefix + 'username\s*:')
    } | Select-Object -First 1
    $passwordLine = $lines | Where-Object {
        $_ -match ($settingPrefix + 'password\s*:')
    } | Select-Object -First 1

    if (-not $urlLine -or -not $userLine -or -not $passwordLine) {
        throw 'Dedicated nexus_test URL, username, or password is missing.'
    }

    $url = Get-YamlScalar $urlLine 'url' $useCommented
    $username = Get-YamlScalar $userLine 'username' $useCommented
    $password = Get-YamlScalar $passwordLine 'password' $useCommented
    if ($url -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/nexus_test(?:\?|$)') {
        throw 'The selected datasource URL is not local nexus_test.'
    }
    if ($username -ne 'nexus_test_user') {
        throw 'The selected datasource account is not nexus_test_user.'
    }
    if ([string]::IsNullOrWhiteSpace($password)) {
        throw 'The selected nexus_test password is empty.'
    }

    return [pscustomobject]@{
        Url = $url
        Username = $username
        Password = $password
        Source = if ($useCommented) { 'saved-commented-test-settings' } else { 'active-test-settings' }
    }
}

function New-RandomSecret([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Send-UnauthenticatedUuid([int]$Port) {
    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    try {
        $response = $client.GetAsync("http://127.0.0.1:$Port/v1/utils/uuid").GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $json = $body | ConvertFrom-Json
            return [pscustomobject]@{
                Status = [int]$response.StatusCode
                Code = $json.code
                MediaType = $response.Content.Headers.ContentType.MediaType
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $client.Dispose()
    }
}

function Assert-AuthenticationBoundary($Result, [string]$PathName) {
    if ($Result.Status -ne 401 -or
        $Result.Code -ne 'INVALID_API_KEY_CREDENTIAL' -or
        $Result.MediaType -ne 'application/json') {
        throw "$PathName did not reach the expected machine authentication boundary."
    }
}

function Send-ByteRequest([string]$Uri) {
    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    try {
        $response = $client.GetAsync($Uri).GetAwaiter().GetResult()
        try {
            return [pscustomobject]@{
                Status = [int]$response.StatusCode
                MediaType = $response.Content.Headers.ContentType.MediaType
                Bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $client.Dispose()
    }
}

function Get-ByteHash([byte[]]$Bytes) {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha256.ComputeHash($Bytes))
    } finally {
        $sha256.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $NginxExe -PathType Leaf)) {
    throw "Nginx executable not found: $NginxExe"
}
if (-not $JavaExe) {
    $javaHomeCandidate = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { '' }
    $knownJavaCandidate = 'D:\java21\bin\java.exe'
    if ($javaHomeCandidate -and (Test-Path -LiteralPath $javaHomeCandidate -PathType Leaf)) {
        $JavaExe = $javaHomeCandidate
    } elseif (Test-Path -LiteralPath $knownJavaCandidate -PathType Leaf) {
        $JavaExe = $knownJavaCandidate
    } else {
        $JavaExe = (Get-Command java -ErrorAction Stop).Source
    }
}
if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
    throw "Java executable not found: $JavaExe"
}

Assert-PortUnused $NginxPort
Assert-PortUnused $BackendPort
$dailyBeforeSignature = (Get-ListeningSnapshot @(80, 8080)) -join "`n"
$database = Get-TestDatabaseSettings
Write-Output "Preflight: schema=nexus_test account=dedicated source=$($database.Source) isolatedPorts=$NginxPort/$BackendPort"

if ($ReleaseJar) {
    $jarPath = [IO.Path]::GetFullPath($ReleaseJar)
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Release JAR was not found: $jarPath"
    }
} else {
    Push-Location $serverPath
    try {
        & .\mvnw.cmd -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    } finally {
        Pop-Location
    }

    $jarPath = Get-ChildItem -LiteralPath (Join-Path $serverPath 'target') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jarPath) { throw 'Packaged application JAR was not found.' }
}

if ($FrontendDist) {
    $FrontendDist = [IO.Path]::GetFullPath($FrontendDist)
    if (-not (Test-Path -LiteralPath (Join-Path $FrontendDist 'index.html') -PathType Leaf)) {
        throw "Frontend dist is missing index.html: $FrontendDist"
    }
}
if (-not $BackendWorkingDirectory) {
    $BackendWorkingDirectory = $serverPath
}
$BackendWorkingDirectory = [IO.Path]::GetFullPath($BackendWorkingDirectory)
if (-not (Test-Path -LiteralPath $BackendWorkingDirectory -PathType Container)) {
    throw "Backend working directory does not exist: $BackendWorkingDirectory"
}
if ($PlaywrightBrowsersPath) {
    $PlaywrightBrowsersPath = [IO.Path]::GetFullPath($PlaywrightBrowsersPath)
    if (-not (Test-Path -LiteralPath $PlaywrightBrowsersPath -PathType Container)) {
        throw "Playwright browser directory does not exist: $PlaywrightBrowsersPath"
    }
}

New-Item -ItemType Directory -Path $runtimeLogsPath -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'temp') -Force | Out-Null

$proxyLocationName = if ($FrontendDist) { '~ ^/(api|v1|s)(/|$)' } else { '/' }
$staticLocation = if ($FrontendDist) {
    $staticRoot = $FrontendDist.Replace('\', '/')
    @"

        location / {
            root "$staticRoot";
            try_files `$uri `$uri/ /index.html;
        }
"@
} else { '' }
$mimeTypes = (Join-Path (Split-Path -Parent $NginxExe) 'conf\mime.types').Replace('\', '/')
$nginxConfig = @"
worker_processes 1;
pid logs/nginx.pid;
error_log logs/error.log notice;

events { worker_connections 128; }

http {
    include "$mimeTypes";
    default_type application/octet-stream;
    log_format deploy_boundary 'peer=`$remote_addr:`$remote_port request="`$request" '
        'status=`$status upstream=`$upstream_addr upstreamStatus=`$upstream_status';
    access_log logs/access.log deploy_boundary;

    server {
        listen 127.0.0.1:$NginxPort;
        server_name localhost;

        location $proxyLocationName {
            proxy_http_version 1.1;
            proxy_set_header Host `$host;
            proxy_set_header X-Real-IP `$remote_addr;
            proxy_set_header X-Forwarded-For `$remote_addr;
            proxy_set_header Forwarded "";
            proxy_connect_timeout 2s;
            proxy_send_timeout 10s;
            proxy_read_timeout 30s;
            proxy_pass http://127.0.0.1:$BackendPort;
        }
$staticLocation
    }
}
"@
Set-Content -LiteralPath $nginxConfigPath -Value $nginxConfig -Encoding utf8

$backendLauncherProcess = $null
$backendProcess = $null
$nginxProcess = $null
$passed = $false
$previousEnvironment = @{}
$environmentNames = @(
    'SPRING_PROFILES_ACTIVE',
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
    'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE',
    'NEXUS_JWT_SECRET',
    'NEXUS_LOG_DIR',
    'PLAYWRIGHT_BROWSERS_PATH')
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:SPRING_DATASOURCE_URL = $database.Url
    $env:SPRING_DATASOURCE_USERNAME = $database.Username
    $env:SPRING_DATASOURCE_PASSWORD = $database.Password
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '2'
    $env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '1'
    $env:NEXUS_JWT_SECRET = New-RandomSecret 32
    $env:NEXUS_LOG_DIR = (Join-Path $runtimePath 'application-logs')
    if ($PlaywrightBrowsersPath) {
        $env:PLAYWRIGHT_BROWSERS_PATH = $PlaywrightBrowsersPath
    }

    $backendLauncherProcess = Start-Process -FilePath $JavaExe `
        -ArgumentList @(
            '-jar',
            ('"' + $jarPath + '"'),
            '--spring.profiles.active=local',
            ('--spring.config.additional-location=file:' +
                ($serverPath.Replace('\', '/') + '/config/')),
            '--server.address=127.0.0.1',
            "--server.port=$BackendPort") `
        -WorkingDirectory $BackendWorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $backendOutputPath `
        -RedirectStandardError $backendErrorPath
    Wait-LoopbackListener $BackendPort 90

    $startup = Get-Content -LiteralPath $backendOutputPath -Raw
    if ($startup -notmatch 'Database: jdbc:mysql://127\.0\.0\.1:3306/nexus_test' -or
        $startup -notmatch "Tomcat started on port $BackendPort\b") {
        throw 'Backend startup log did not prove nexus_test and the expected port.'
    }
    $pidMatch = [regex]::Match($startup, 'Starting NexusServerApplication.* with PID (\d+)')
    if (-not $pidMatch.Success) { throw 'Backend PID was not found in the startup log.' }
    $backendProcess = Get-Process -Id ([int]$pidMatch.Groups[1].Value) -ErrorAction Stop

    $backendListeners = @(Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction Stop)
    if ($backendListeners.Count -ne 1 -or
        $backendListeners[0].LocalAddress -ne '127.0.0.1' -or
        $backendListeners[0].OwningProcess -ne $backendProcess.Id) {
        throw 'Backend was not exclusively bound to the expected IPv4 loopback endpoint.'
    }
    Write-Output "Backend: pid=$($backendProcess.Id) bind=127.0.0.1:$BackendPort schema=nexus_test"

    $direct = Send-UnauthenticatedUuid $BackendPort
    Assert-AuthenticationBoundary $direct 'Direct loopback request'
    Write-Output "Direct loopback: status=$($direct.Status) code=$($direct.Code)"

    $nonLoopbackAddresses = @(
        Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.AddressState -eq 'Preferred' -and
                -not $_.SkipAsSource -and
                $_.IPAddress -notmatch '^127\.' -and
                $_.IPAddress -notmatch '^169\.254\.'
            } |
            Select-Object -ExpandProperty IPAddress -Unique
    )
    if ($nonLoopbackAddresses.Count -eq 0) {
        throw 'No preferred non-loopback IPv4 address was available for the negative connection check.'
    }
    foreach ($address in $nonLoopbackAddresses) {
        if (Test-TcpConnection $address $BackendPort) {
            throw 'A non-loopback IPv4 address unexpectedly reached the backend.'
        }
    }
    Write-Output "Non-loopback direct: checked=$($nonLoopbackAddresses.Count) allRefused=true"

    & $NginxExe -p $nginxPrefix -c $nginxConfigPath -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed.' }
    $nginxProcess = Start-Process -FilePath $NginxExe `
        -ArgumentList @('-p', ('"' + $nginxPrefix + '"'), '-c', ('"' + $nginxConfigPath + '"')) `
        -WorkingDirectory $runtimePath -WindowStyle Hidden -PassThru
    Wait-LoopbackListener $NginxPort 20

    $proxied = Send-UnauthenticatedUuid $NginxPort
    Assert-AuthenticationBoundary $proxied 'Proxied loopback request'
    Write-Output "Nginx proxy: listener=127.0.0.1:$NginxPort upstream=127.0.0.1:$BackendPort status=$($proxied.Status) code=$($proxied.Code)"

    if ($FrontendDist) {
        $indexPath = Join-Path $FrontendDist 'index.html'
        $index = Send-ByteRequest "http://127.0.0.1:$NginxPort/"
        if ($index.Status -ne 200 -or $index.MediaType -ne 'text/html' -or
            (Get-ByteHash $index.Bytes) -ne (Get-FileHash $indexPath -Algorithm SHA256).Hash) {
            throw 'Nginx did not return the exact release index.html.'
        }

        $spa = Send-ByteRequest "http://127.0.0.1:$NginxPort/applications"
        if ($spa.Status -ne 200 -or
            (Get-ByteHash $spa.Bytes) -ne (Get-ByteHash $index.Bytes)) {
            throw 'Nginx SPA fallback did not return release index.html.'
        }

        $assetPath = Get-ChildItem -LiteralPath (Join-Path $FrontendDist 'assets') `
            -File -Filter '*.js' | Sort-Object Name | Select-Object -First 1
        if ($null -eq $assetPath) { throw 'Release dist contains no JavaScript asset.' }
        $asset = Send-ByteRequest ("http://127.0.0.1:{0}/assets/{1}" -f `
                $NginxPort, $assetPath.Name)
        if ($asset.Status -ne 200 -or
            (Get-ByteHash $asset.Bytes) -ne
            (Get-FileHash $assetPath.FullName -Algorithm SHA256).Hash) {
            throw 'Nginx did not return the exact release JavaScript asset.'
        }
        Write-Output "Nginx static: index=exact spaFallback=exact asset=$($assetPath.Name) exact=true"
    }

    $accessLines = @(Get-Content -LiteralPath (Join-Path $runtimeLogsPath 'access.log'))
    $proxyAccessLines = @($accessLines | Where-Object {
            $_ -match "status=401 upstream=127\.0\.0\.1:$BackendPort upstreamStatus=401"
        })
    if ($proxyAccessLines.Count -ne 1) {
        throw 'Nginx access log did not prove the expected Java upstream response.'
    }

    $dailyAfterSignature = (Get-ListeningSnapshot @(80, 8080)) -join "`n"
    if ($dailyBeforeSignature -ne $dailyAfterSignature) {
        throw 'The daily 80/8080 listener snapshot changed during the experiment.'
    }
    Write-Output 'Daily listeners: ports=80/8080 unchanged=true'
    $passed = $true
    Write-Output "PASS: Spring loopback boundary verified. Runtime=$runtimePath"
} finally {
    if ($null -ne $nginxProcess) {
        try { & $NginxExe -p $nginxPrefix -c $nginxConfigPath -s quit 2>$null } catch {}
        try {
            if (-not $nginxProcess.HasExited -and -not $nginxProcess.WaitForExit(5000)) {
                $nginxProcess.Kill()
                $nginxProcess.WaitForExit(5000) | Out-Null
            }
        } catch {}
    }
    if ($null -ne $backendProcess) {
        try {
            if (-not $backendProcess.HasExited) {
                $backendProcess.Kill()
                $backendProcess.WaitForExit(10000) | Out-Null
            }
        } catch {}
    }
    if ($null -ne $backendLauncherProcess) {
        try {
            if (-not $backendLauncherProcess.HasExited) {
                $backendLauncherProcess.Kill()
                $backendLauncherProcess.WaitForExit(5000) | Out-Null
            }
        } catch {}
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if (-not $passed) {
        Write-Output "FAIL: inspect runtime logs at $runtimePath"
    }
}
