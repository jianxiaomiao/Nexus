param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = '',
    [int]$NginxPort = 18080,
    [int]$BackendPort = 18081,
    [string]$ArticleUrl = 'https://blog.csdn.net/qq_46429858/article/details/115696591',
    [switch]$SkipScreenshot
)

$ErrorActionPreference = 'Stop'
$serverPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localConfigPath = Join-Path $serverPath 'config/application-local.yml'
$runId = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff') + "-$PID"
$runtimePath = Join-Path $serverPath "target/rate-limit-nginx-acceptance-$NginxPort-$BackendPort-$runId"
$runtimeLogsPath = Join-Path $runtimePath 'logs'
$backendOutputPath = Join-Path $runtimePath 'backend-output.log'
$backendErrorPath = Join-Path $runtimePath 'backend-error.log'
$nginxConfigPath = Join-Path $runtimePath 'nginx.conf'
$nginxPrefix = $runtimePath.Replace('\', '/') + '/'
$origin = [Uri]"http://127.0.0.1:$NginxPort"

function Test-Listener([int]$Port) {
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

function Wait-Listener([int]$Port, [Diagnostics.Process]$Process, [int]$Seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Process exited before port $Port opened."
        }
        if (Test-Listener $Port) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "Port $Port did not open within $Seconds seconds."
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

function Send-Request(
    [Net.Http.HttpClient]$Client,
    [string]$Path,
    [string]$Method = 'GET',
    $Body = $null,
    [string]$Authorization = '') {

    $request = [Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::new($Method),
        [Uri]::new($origin, $Path))
    try {
        if ($Authorization) {
            $request.Headers.TryAddWithoutValidation('Authorization', $Authorization) | Out-Null
        }
        if ($null -ne $Body) {
            $request.Content = [Net.Http.StringContent]::new(
                ($Body | ConvertTo-Json -Compress),
                [Text.Encoding]::UTF8,
                'application/json')
        }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $watch.Stop()
        try {
            return Convert-Response $response $watch.Elapsed.TotalMilliseconds
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

function Start-PendingRequest(
    [Net.Http.HttpClient]$Client,
    [string]$Path,
    [string]$Authorization) {

    $request = [Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::Get,
        [Uri]::new($origin, $Path))
    $request.Headers.TryAddWithoutValidation('Authorization', $Authorization) | Out-Null
    return [pscustomobject]@{
        Request = $request
        Task = $Client.SendAsync($request)
    }
}

function Complete-PendingRequests($Pending) {
    $results = [Collections.Generic.List[object]]::new()
    foreach ($item in $Pending) {
        $response = $item.Task.GetAwaiter().GetResult()
        try {
            $results.Add((Convert-Response $response $null))
        } finally {
            $response.Dispose()
            $item.Request.Dispose()
        }
    }
    return @($results)
}

function Convert-Response([Net.Http.HttpResponseMessage]$Response, $DurationMs) {
    $bytes = $Response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $mediaType = if ($null -ne $Response.Content.Headers.ContentType) {
        $Response.Content.Headers.ContentType.MediaType
    } else { '' }
    $json = $null
    if ($mediaType -eq 'application/json' -and $bytes.Length -gt 0) {
        $json = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    }
    $retryAfter = if ($Response.Headers.Contains('Retry-After')) {
        $Response.Headers.GetValues('Retry-After') -join ','
    } else { $null }
    return [pscustomobject]@{
        Status = [int]$Response.StatusCode
        Json = $json
        Bytes = $bytes
        MediaType = $mediaType
        RetryAfter = $retryAfter
        DurationMs = $DurationMs
    }
}

function Send-Management(
    [Net.Http.HttpClient]$Client,
    [string]$Path,
    [string]$Method,
    $Body,
    [string]$Authorization = '') {

    $result = Send-Request $Client $Path $Method $Body $Authorization
    if ($result.Status -lt 200 -or $result.Status -ge 300 -or
        $null -eq $result.Json -or $result.Json.code -ne 'SUCCESS') {
        throw "Management request failed: $Path HTTP $($result.Status)"
    }
    return $result.Json.data
}

function Assert-Png($Result) {
    if ($Result.Status -ne 200 -or $Result.MediaType -ne 'image/png') {
        $code = if ($null -ne $Result.Json) { $Result.Json.code } else { '<non-json>' }
        throw "Screenshot did not return PNG: HTTP $($Result.Status), code=$code"
    }
    $signature = @(137, 80, 78, 71, 13, 10, 26, 10)
    if ($Result.Bytes.Length -lt $signature.Count) { throw 'PNG response is too short.' }
    for ($index = 0; $index -lt $signature.Count; $index++) {
        if ($Result.Bytes[$index] -ne $signature[$index]) {
            throw 'Screenshot response has an invalid PNG signature.'
        }
    }
}

if (-not (Test-Path -LiteralPath $NginxExe -PathType Leaf)) {
    throw "Nginx executable not found: $NginxExe"
}
if (-not $JavaExe) {
    $javaHomeCandidate = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else { '' }
    if ($javaHomeCandidate -and
        (Test-Path -LiteralPath $javaHomeCandidate -PathType Leaf)) {
        $JavaExe = $javaHomeCandidate
    } else {
        $javaCommand = Get-Command java -ErrorAction Stop
        $JavaExe = $javaCommand.Source
    }
}
if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
    throw "Java executable not found: $JavaExe"
}
foreach ($port in @($NginxPort, $BackendPort)) {
    if (Test-Listener $port) {
        throw "Port $port is already in use; no process was changed."
    }
}

$database = Get-TestDatabaseSettings
Write-Output "Preflight: schema=nexus_test account=dedicated source=$($database.Source) ports=$NginxPort/$BackendPort"

Push-Location $serverPath
try {
    & .\mvnw.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
} finally {
    Pop-Location
}
$jarPath = Get-ChildItem -LiteralPath (Join-Path $serverPath 'target') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike 'original-*' } |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $jarPath) { throw 'Packaged application JAR was not found.' }

New-Item -ItemType Directory -Path $runtimeLogsPath -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'temp') -Force | Out-Null
foreach ($path in @($backendOutputPath, $backendErrorPath,
        (Join-Path $runtimeLogsPath 'access.log'),
        (Join-Path $runtimeLogsPath 'error.log'),
        (Join-Path $runtimeLogsPath 'nginx.pid'))) {
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Remove-Item -LiteralPath $path -Force
    }
}

$nginxConfig = @"
worker_processes 1;
pid logs/nginx.pid;
error_log logs/error.log notice;

events { worker_connections 512; }

http {
    log_format nexus_acceptance 'peer=`$remote_addr:`$remote_port '
        'request="`$request" status=`$status upstream=`$upstream_addr '
        'upstreamStatus=`$upstream_status requestTime=`$request_time '
        'upstreamTime=`$upstream_response_time';
    access_log logs/access.log nexus_acceptance;

    server {
        listen 127.0.0.1:$NginxPort;
        server_name localhost;

        location / {
            proxy_http_version 1.1;
            proxy_set_header Host `$host;
            proxy_set_header X-Real-IP `$remote_addr;
            proxy_set_header X-Forwarded-For `$remote_addr;
            proxy_connect_timeout 2s;
            proxy_send_timeout 30s;
            proxy_read_timeout 30s;
            proxy_pass http://127.0.0.1:$BackendPort;
        }
    }
}
"@
Set-Content -LiteralPath $nginxConfigPath -Value $nginxConfig -Encoding utf8

$backendProcess = $null
$backendLauncherProcess = $null
$nginxProcess = $null
$client = $null
$applicationId = $null
$bearer = ''
$fullKeyA = ''
$fullKeyB = ''
$password = ''
$passed = $false
$previousEnvironment = @{}
$environmentNames = @(
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
    'NEXUS_JWT_SECRET',
    'NEXUS_LOG_DIR')
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:SPRING_DATASOURCE_URL = $database.Url
    $env:SPRING_DATASOURCE_USERNAME = $database.Username
    $env:SPRING_DATASOURCE_PASSWORD = $database.Password
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '4'
    $env:NEXUS_JWT_SECRET = New-RandomSecret 32
    $env:NEXUS_LOG_DIR = (Join-Path $runtimePath 'application-logs')

    $backendLauncherProcess = Start-Process -FilePath $JavaExe `
        -ArgumentList @('-jar', ('"' + $jarPath + '"'), "--server.port=$BackendPort") `
        -WorkingDirectory $serverPath -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $backendOutputPath `
        -RedirectStandardError $backendErrorPath
    Wait-Listener $BackendPort $backendLauncherProcess 90

    $startup = Get-Content -LiteralPath $backendOutputPath -Raw
    if ($startup -notmatch 'Database: jdbc:mysql://127\.0\.0\.1:3306/nexus_test' -or
        $startup -notmatch "Tomcat started on port $BackendPort\b") {
        throw 'Backend startup log did not prove nexus_test and the expected port.'
    }
    $pidMatch = [regex]::Match(
        $startup,
        'Starting NexusServerApplication.* with PID (\d+)')
    $listener = Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction Stop
    if (-not $pidMatch.Success -or
        [int]$pidMatch.Groups[1].Value -notin $listener.OwningProcess) {
        throw 'Backend listener does not belong to the process started by this script.'
    }
    $backendProcess = Get-Process -Id ([int]$pidMatch.Groups[1].Value) -ErrorAction Stop
    Write-Output "Backend: pid=$($backendProcess.Id) schema=nexus_test port=$BackendPort"

    & $NginxExe -p $nginxPrefix -c $nginxConfigPath -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed.' }
    $nginxProcess = Start-Process -FilePath $NginxExe `
        -ArgumentList @('-p', ('"' + $nginxPrefix + '"'), '-c', ('"' + $nginxConfigPath + '"')) `
        -WorkingDirectory $runtimePath -WindowStyle Hidden -PassThru
    Wait-Listener $NginxPort $nginxProcess 20
    Write-Output "Nginx: isolated listener=http://127.0.0.1:$NginxPort upstream=127.0.0.1:$BackendPort"

    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(45)

    $invalid = Send-Request $client '/v1/utils/uuid' 'GET' $null 'ApiKey invalid-acceptance-key'
    if ($invalid.Status -ne 401 -or $invalid.Json.code -ne 'INVALID_API_KEY_CREDENTIAL') {
        throw "Invalid credential boundary failed: HTTP $($invalid.Status)"
    }

    $suffix = [Guid]::NewGuid().ToString('N')
    $email = "rate-limit-accept-$suffix@example.test"
    $password = New-RandomSecret 24
    Send-Management $client '/api/auth/register' 'POST' @{
        email = $email
        displayName = 'Rate limit Nginx acceptance'
        password = $password
    } | Out-Null
    $login = Send-Management $client '/api/auth/login' 'POST' @{
        email = $email
        password = $password
    }
    $bearer = 'Bearer ' + $login.accessToken
    $application = Send-Management $client '/api/application/create' 'POST' @{
        name = "rate-limit-accept-$suffix"
    } $bearer
    $applicationId = $application.id
    $keyA = Send-Management $client '/api/apiKey/create' 'POST' @{
        applicationId = $applicationId
        name = 'Rate acceptance Key A'
    } $bearer
    $keyB = Send-Management $client '/api/apiKey/create' 'POST' @{
        applicationId = $applicationId
        name = 'Rate acceptance Key B'
    } $bearer
    $fullKeyA = $keyA.apiKey
    $fullKeyB = $keyB.apiKey
    Write-Output "Resources: Application=$applicationId KeyA=$($keyA.id) KeyB=$($keyB.id)"

    $quotaBefore = Send-Management $client "/api/application/$applicationId/quota" 'GET' $null $bearer
    if ([int]$quotaBefore.usedCount -ne 0) { throw 'Fresh Application quota was not zero.' }

    $threePending = @(
        Start-PendingRequest $client '/v1/utils/uuid' ('ApiKey ' + $fullKeyA)
        Start-PendingRequest $client '/v1/utils/uuid' ('ApiKey ' + $fullKeyB)
        Start-PendingRequest $client '/v1/utils/uuid' ('ApiKey ' + $fullKeyA)
    )
    $threeResults = Complete-PendingRequests $threePending
    if (@($threeResults | Where-Object { $_.Status -ne 200 -or $_.Json.code -ne 'SUCCESS' }).Count -ne 0) {
        throw 'The three-request queued-allowance batch did not return three successful JSON responses.'
    }
    $quotaAfterThree = Send-Management $client "/api/application/$applicationId/quota" 'GET' $null $bearer
    if ([int]$quotaAfterThree.usedCount -ne 3) {
        throw "Quota after queued allowance should be 3, got $($quotaAfterThree.usedCount)."
    }
    Write-Output 'Queued allowance: 3/3 HTTP 200; two Keys shared one Application quota; usedCount=3'

    Start-Sleep -Milliseconds 250
    $pressurePending = [Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt 30; $index++) {
        $credential = if ($index % 2 -eq 0) { $fullKeyA } else { $fullKeyB }
        $pressurePending.Add((Start-PendingRequest $client '/v1/utils/uuid' ('ApiKey ' + $credential)))
    }
    $pressureResults = Complete-PendingRequests @($pressurePending)
    $allowedCount = @($pressureResults | Where-Object { $_.Status -eq 200 }).Count
    $rejected = @($pressureResults | Where-Object { $_.Status -eq 429 })
    $unexpected = @($pressureResults | Where-Object { $_.Status -notin @(200, 429) })
    if ($allowedCount -lt 1 -or $rejected.Count -lt 1 -or $unexpected.Count -ne 0) {
        throw "Pressure batch expected both 200 and 429 only; 200=$allowedCount 429=$($rejected.Count) unexpected=$($unexpected.Count)."
    }
    foreach ($result in $rejected) {
        if ($result.Json.code -notin @('APPLICATION_RATE_QUEUE_FULL', 'APPLICATION_RATE_WAIT_TIMEOUT') -or
            $result.RetryAfter -ne '1') {
            throw 'A 429 response did not preserve its JSON code and Retry-After header.'
        }
    }
    $quotaAfterPressure = Send-Management $client "/api/application/$applicationId/quota" 'GET' $null $bearer
    $expectedUsed = 3 + $allowedCount
    if ([int]$quotaAfterPressure.usedCount -ne $expectedUsed) {
        throw "Rate-rejected requests changed quota: expected=$expectedUsed actual=$($quotaAfterPressure.usedCount)."
    }
    $queueFullCount = @($rejected | Where-Object { $_.Json.code -eq 'APPLICATION_RATE_QUEUE_FULL' }).Count
    $timeoutCount = @($rejected | Where-Object { $_.Json.code -eq 'APPLICATION_RATE_WAIT_TIMEOUT' }).Count
    Write-Output "Pressure: total=30 200=$allowedCount queueFull=$queueFullCount timedOut=$timeoutCount quotaUsed=$expectedUsed"

    if (-not $SkipScreenshot) {
        Start-Sleep -Milliseconds 1200
        $quotaBeforePng = Send-Management $client "/api/application/$applicationId/quota" 'GET' $null $bearer
        $png = Send-Request $client '/v1/web/screenshot' 'POST' @{ url = $ArticleUrl } ('ApiKey ' + $fullKeyA)
        Assert-Png $png
        $quotaAfterPng = Send-Management $client "/api/application/$applicationId/quota" 'GET' $null $bearer
        if ([int]$quotaAfterPng.usedCount -ne ([int]$quotaBeforePng.usedCount + 1)) {
            throw 'Successful screenshot did not consume exactly one quota unit.'
        }
        Write-Output "PNG: HTTP 200 contentType=image/png bytes=$($png.Bytes.Length) quotaDelta=1"
    }

    Start-Sleep -Milliseconds 200
    $accessLogPath = Join-Path $runtimeLogsPath 'access.log'
    $accessLines = @(Get-Content -LiteralPath $accessLogPath)
    $mismatchedStatus = @($accessLines | Where-Object {
        $_ -match ' status=([0-9]+) .*upstreamStatus=([0-9]+)' -and $Matches[1] -ne $Matches[2]
    })
    if ($mismatchedStatus.Count -ne 0) {
        throw 'Nginx downstream status differed from the Java upstream status.'
    }
    $combinedLogs = ($accessLines -join "`n") + "`n" +
        (Get-Content -LiteralPath $backendOutputPath -Raw) + "`n" +
        (Get-Content -LiteralPath $backendErrorPath -Raw)
    if ($combinedLogs.Contains($fullKeyA) -or $combinedLogs.Contains($fullKeyB) -or
        $combinedLogs.Contains($login.accessToken)) {
        throw 'A credential appeared in acceptance logs.'
    }
    $nginx429 = @($accessLines | Where-Object { $_ -match ' status=429 ' }).Count
    if ($nginx429 -ne $rejected.Count) {
        throw "Nginx 429 log count mismatch: expected=$($rejected.Count) actual=$nginx429"
    }
    Write-Output "Logs: entries=$($accessLines.Count) nginx429=$nginx429 upstreamStatusMatched=true credentialsAbsent=true"
    $passed = $true
} finally {
    try {
        if ($applicationId -and $bearer -and $null -ne $client -and
            $null -ne $nginxProcess -and -not $nginxProcess.HasExited) {
            try {
                Send-Management $client "/api/application/$applicationId" 'DELETE' $null $bearer | Out-Null
                Write-Output "Cleanup: Application=$applicationId soft-deleted with both Keys; random account remains in nexus_test."
            } catch {
                Write-Warning "Application cleanup failed: $($_.Exception.Message)"
            }
        }
    } finally {
        $fullKeyA = ''
        $fullKeyB = ''
        $bearer = ''
        $password = ''
        if ($null -ne $client) { $client.Dispose() }
        if ($null -ne $nginxProcess -and -not $nginxProcess.HasExited) {
            & $NginxExe -p $nginxPrefix -c $nginxConfigPath -s quit 2>$null
            if (-not $nginxProcess.WaitForExit(5000)) {
                $nginxProcess.Kill()
                $nginxProcess.WaitForExit(5000) | Out-Null
            }
        }
        if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
            $backendProcess.Kill()
            $backendProcess.WaitForExit(10000) | Out-Null
        }
        if ($null -ne $backendLauncherProcess -and
            -not $backendLauncherProcess.HasExited) {
            $backendLauncherProcess.Kill()
            $backendLauncherProcess.WaitForExit(5000) | Out-Null
        }
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}

if ($passed) {
    Write-Output 'PASS: real Spring + MySQL + Nginx rate-limit acceptance completed.'
}
