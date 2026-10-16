param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$observations = {
    $successfulCases = [System.Collections.Generic.List[object]]::new()

    function Invoke-EdgeCase(
        [string]$Name,
        [string[]]$Headers,
        [string]$ExpectedIp,
        [string]$ExpectedPort = ''
    ) {
        Write-Output $Name
        $curlArguments = @(
            '--silent', '--show-error', '--max-time', '5', '--noproxy', '*',
            '--write-out', "`n__STATUS__:%{http_code}",
            ('http://127.0.0.1:18080/edge?case=' + $Name)
        )
        foreach ($header in $Headers) {
            $curlArguments += @('--header', $header)
        }

        $responseLines = & curl.exe @curlArguments
        if ($LASTEXITCODE -ne 0) { throw "Observation request failed: $Name" }
        $responseText = $responseLines -join "`n"
        Write-Output $responseText
        if ($responseText -notmatch '(?m)^__STATUS__:200$') {
            throw "Expected HTTP 200: $Name"
        }
        if ($responseText -notmatch '(?m)^peer=127\.0\.0\.1:[0-9]+$' -or
                $responseText -notmatch '(?m)^local=127\.0\.0\.1:18081$') {
            throw "Unexpected Java connection endpoints: $Name"
        }
        foreach ($field in @('xRealIp', 'xForwardedFor')) {
            $expectedLine = [regex]::Escape($field + '=' + $ExpectedIp)
            if ($responseText -notmatch ('(?m)^' + $expectedLine + '$')) {
                throw "Unexpected $field in case: $Name"
            }
        }
        [void]$successfulCases.Add([pscustomobject]@{
            Name = $Name
            ExpectedIp = $ExpectedIp
            ExpectedPort = $ExpectedPort
        })
    }

    Invoke-EdgeCase 'missing' @() '127.0.0.1'
    Invoke-EdgeCase 'duplicate' @(
        'X-Forwarded-For: 203.0.113.7, 192.0.2.10',
        'X-Forwarded-For: 198.51.100.20'
    ) '192.0.2.10'
    Invoke-EdgeCase 'malformed-rightmost' @(
        'X-Forwarded-For: 203.0.113.7, not-an-ip'
    ) '127.0.0.1'
    Invoke-EdgeCase 'malformed-left' @(
        'X-Forwarded-For: not-an-ip, 198.51.100.20'
    ) '198.51.100.20'
    Invoke-EdgeCase 'ipv6' @(
        'X-Forwarded-For: 2001:db8::7, 198.51.100.20'
    ) '2001:db8::7'
    Invoke-EdgeCase 'ipv6-port' @(
        'X-Forwarded-For: [2001:db8::8]:4321, 198.51.100.20'
    ) '2001:db8::8' '4321'

    $tabValue = "203.0.113.7`tLOG-INJECTION, 198.51.100.20"
    Invoke-EdgeCase 'control-character' @(
        ('X-Forwarded-For: ' + $tabValue)
    ) '198.51.100.20'

    $longMalformed = 'x' * 4096
    Invoke-EdgeCase 'long-within-limit' @(
        ('X-Forwarded-For: ' + $longMalformed)
    ) '127.0.0.1'

    Write-Output 'oversized-single-header'
    $oversized = 'x' * 9000
    $oversizedOutput = & curl.exe --silent --show-error --max-time 5 --noproxy '*' `
        --output NUL --write-out '%{http_code}' `
        --header ('X-Forwarded-For: ' + $oversized) `
        'http://127.0.0.1:18080/edge?case=oversized-single-header'
    if ($LASTEXITCODE -ne 0) { throw 'Oversized observation request failed.' }
    Write-Output ("HTTP status=" + $oversizedOutput)
    if (($oversizedOutput -join '') -ne '400') {
        throw 'Expected oversized single header field to return HTTP 400.'
    }

    $logPath = Join-Path $runtimePath 'logs/access.log'
    $logLines = @(Get-Content -LiteralPath $logPath)
    Write-Output 'Nginx evidence summary:'
    foreach ($case in $successfulCases) {
        $line = $logLines | Where-Object { $_ -match ('case=' + [regex]::Escape($case.Name) + ' ') }
        if (@($line).Count -ne 1) {
            throw "Expected exactly one log line for $($case.Name)."
        }
        if ($line -notmatch 'connectionPeer=\[127\.0\.0\.1\]:[0-9]+ ' -or
                $line -notmatch ('effectiveIp=' + [regex]::Escape($case.ExpectedIp) + ' ') -or
                $line -notmatch 'status=200$') {
            throw "Unexpected Nginx log evidence for $($case.Name)."
        }
        if ($case.ExpectedPort -ne '' -and
                $line -notmatch ('effectivePort="' + $case.ExpectedPort + '" ')) {
            throw "Expected restored port for $($case.Name)."
        }
        Write-Output ("{0}: effective={1} status=200 logChars={2}" -f `
                $case.Name, $case.ExpectedIp, $line.Length)
    }

    $oversizedLine = $logLines | Where-Object { $_ -match 'case=oversized-single-header ' }
    if (@($oversizedLine).Count -ne 1 -or $oversizedLine -notmatch 'status=400$') {
        throw 'Expected exactly one HTTP 400 log line for the oversized header.'
    }
    Write-Output ("oversized-single-header: status=400 logChars=" + $oversizedLine.Length)

    $controlLine = $logLines | Where-Object { $_ -match 'case=control-character ' }
    if ($controlLine -notmatch '\\tLOG-INJECTION') {
        throw 'Expected JSON-escaped tab in the access log.'
    }
    if ($logLines.Count -ne 9) {
        throw "Expected one physical access-log line per request; found $($logLines.Count)."
    }
    Write-Output 'All nine requests produced exactly one physical access-log line; the tab was escaped.'
}

$runtimeDirectory = Join-Path $PSScriptRoot 'runtime'
foreach ($staleLog in @(
    (Join-Path $runtimeDirectory 'logs/access.log'),
    (Join-Path $runtimeDirectory 'logs/error.log'),
    (Join-Path $runtimeDirectory 'java-output.log'),
    (Join-Path $runtimeDirectory 'java-error.log'),
    (Join-Path $runtimeDirectory 'observations.log')
)) {
    Remove-Item -LiteralPath $staleLog -Force -ErrorAction SilentlyContinue
}

& (Join-Path $PSScriptRoot '../ip-1/run.ps1') `
    -NginxExe $NginxExe -JavaExe $JavaExe `
    -NginxConfig (Join-Path $PSScriptRoot 'nginx.conf') `
    -RuntimeDirectory $runtimeDirectory `
    -ObservationScript $observations
