param(
    [Parameter(Mandatory = $true)][string]$StartupLogPath,
    [string]$BaseUri = 'http://127.0.0.1:18080',
    [string]$ArticleUrl = 'https://blog.csdn.net/qq_46429858/article/details/115696591'
)

$ErrorActionPreference = 'Stop'
$origin = [Uri]$BaseUri
if ($origin.Scheme -ne 'http' -or $origin.Host -notin @('localhost', '127.0.0.1') -or $origin.UserInfo) {
    throw 'Only a local, disposable test backend is supported.'
}
$startup = Get-Content -LiteralPath $StartupLogPath -Raw
if ($startup -notmatch 'schema [`'']nexus_test[`'']' -or
    $startup -notmatch ('Tomcat started on port ' + $origin.Port + '\b')) {
    throw 'Verify that this startup log belongs to a running nexus_test backend at the selected port.'
}
$pidMatch = [regex]::Match($startup, 'INFO\s+(\d+)\s+---[^\r\n]*Tomcat started on port ' + $origin.Port + '\b')
$listener = Get-NetTCPConnection -State Listen -LocalPort $origin.Port -ErrorAction Stop
if (-not $pidMatch.Success -or [int]$pidMatch.Groups[1].Value -notin $listener.OwningProcess) {
    throw 'The listening process does not match the verified test-backend startup log.'
}

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(60)
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
function New-RandomText([int]$Length) {
    $bytes = [byte[]]::new($Length)
    $random.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}
function Send-Request([string]$Path, [string]$Method = 'GET', $Body = $null, [string]$Authorization = '') {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), [Uri]::new($origin, $Path))
    try {
        if ($Authorization) { $request.Headers.TryAddWithoutValidation('Authorization', $Authorization) | Out-Null }
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            $json = $null
            if ($response.Content.Headers.ContentType.MediaType -eq 'application/json') {
                $json = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
            }
            $blocked = $null
            $limitReached = $null
            if ($response.Headers.Contains('X-Nexus-Blocked-Resources')) {
                $blocked = ($response.Headers.GetValues('X-Nexus-Blocked-Resources') -join ',')
            }
            if ($response.Headers.Contains('X-Nexus-Resource-Limit-Reached')) {
                $limitReached = ($response.Headers.GetValues('X-Nexus-Resource-Limit-Reached') -join ',')
            }
            return [pscustomobject]@{ Status = [int]$response.StatusCode; Json = $json; Bytes = $bytes; Blocked = $blocked; LimitReached = $limitReached }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Send-Management([string]$Path, [string]$Method, $Body, [string]$Authorization = '') {
    $result = Send-Request $Path $Method $Body $Authorization
    if ($result.Status -lt 200 -or $result.Status -ge 300 -or $result.Json.code -ne 'SUCCESS') {
        throw "Management request failed: $Path HTTP $($result.Status)"
    }
    return $result.Json.data
}

$suffix = [Guid]::NewGuid().ToString('N')
$email = "screenshot-diagnose-$suffix@example.test"
$password = New-RandomText 24
$bearer = ''
$fullKey = ''
$applicationId = $null
try {
    Send-Management '/api/auth/register' 'POST' @{ email = $email; displayName = 'Screenshot diagnosis'; password = $password } | Out-Null
    $login = Send-Management '/api/auth/login' 'POST' @{ email = $email; password = $password }
    $bearer = 'Bearer ' + $login.accessToken
    $application = Send-Management '/api/application/create' 'POST' @{ name = "screenshot-diagnose-$suffix" } $bearer
    $applicationId = $application.id
    $key = Send-Management '/api/apiKey/create' 'POST' @{ applicationId = $applicationId; name = 'Fresh screenshot test Key' } $bearer
    $fullKey = $key.apiKey
    Write-Output "Created fresh test resources: Application=$applicationId, Key=$($key.id)"
    $logOffset = @(Get-Content -LiteralPath $StartupLogPath).Count
    $result = Send-Request '/v1/web/screenshot' 'POST' @{ url = $ArticleUrl } ('ApiKey ' + $fullKey)
    Write-Output "Screenshot HTTP $($result.Status)"
    if ($result.Status -eq 200) {
        $pngSignature = @(137, 80, 78, 71, 13, 10, 26, 10)
        if ($result.Bytes.Length -lt 8) { throw 'The response is not a PNG.' }
        for ($index = 0; $index -lt 8; $index++) {
            if ($result.Bytes[$index] -ne $pngSignature[$index]) { throw 'The response is not a PNG.' }
        }
        $pngPath = Join-Path ([IO.Path]::GetTempPath()) "nexus-screenshot-$suffix.png"
        [IO.File]::WriteAllBytes($pngPath, $result.Bytes)
        Write-Output "PNG=$pngPath; bytes=$($result.Bytes.Length); blocked=$($result.Blocked); limitReached=$($result.LimitReached)"
    } elseif ($null -ne $result.Json) {
        # Non-2xx bodies are read normally; no dependence on PowerShell ErrorDetails/OutFile behavior.
        Write-Output "code=$($result.Json.code); message=$($result.Json.message)"
    } else { throw 'The failure response was not JSON.' }
    Get-Content -LiteralPath $StartupLogPath | Select-Object -Skip $logOffset |
        Where-Object { $_ -match 'path=/v1/web/screenshot, status=|截图资源统计' } | ForEach-Object { Write-Output $_ }
} finally {
    try {
        if ($applicationId -and $bearer) {
            Send-Management "/api/application/$applicationId" 'DELETE' $null $bearer | Out-Null
            Write-Output "Cleanup: Application=$applicationId soft-deleted with its Key; random account remains in nexus_test."
        }
    } finally {
        $fullKey = ''; $bearer = ''; $password = ''; $key = $null; $login = $null
        $random.Dispose(); $client.Dispose()
    }
}
