param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$observations = {
    function Invoke-Case([string]$Title, [string]$Url, [string]$ExpectedIp) {
        Write-Output $Title
        $responseLines = & curl.exe --silent --show-error --fail --max-time 5 --noproxy '*' $Url
        if ($LASTEXITCODE -ne 0) { throw "Observation request failed: $Title" }
        $responseText = $responseLines -join "`n"
        Write-Output $responseText
        if ($responseText -notmatch '(?m)^peer=127\.0\.0\.1:[0-9]+$' -or
                $responseText -notmatch '(?m)^local=127\.0\.0\.1:18081$') {
            throw "Unexpected Java connection endpoints: $Title"
        }
        foreach ($field in @('xRealIp', 'xForwardedFor')) {
            $expectedLine = [regex]::Escape($field + '=' + $ExpectedIp)
            if ($responseText -notmatch ('(?m)^' + $expectedLine + '$')) {
                throw "Unexpected $field in case: $Title"
            }
        }
        Write-Output 'Java endpoints and forwarded IP values match the prediction.'
    }

    Invoke-Case '1. IPv6 client -> outer -> inner without restoration -> Java' `
        'http://[::1]:18080/raw' '127.0.0.1'
    Invoke-Case '2. Same IPv6 client -> outer -> inner with restoration -> Java' `
        'http://[::1]:18080/restored' '::1'
    Invoke-Case '3. IPv4 client -> outer -> inner with restoration -> Java (control)' `
        'http://127.0.0.1:18080/restored' '127.0.0.1'

    Write-Output 'Both proxy hops, including original connection endpoints:'
    $logLines = Get-Content -LiteralPath (Join-Path $runtimePath 'logs/access.log') -Tail 6
    $logLines | Write-Output
    if (!($logLines | Where-Object {
        $_ -match 'listener=\[127\.0\.0\.1\]:18082 connectionPeer=\[127\.0\.0\.1\]:[0-9]+ effectiveIp=::1 ' -and
        $_ -match 'route=/restored .*status=200$'
    })) {
        throw 'Missing inner-proxy evidence that the IPv6 IP was restored while the IPv4 connection peer was retained.'
    }
}

& (Join-Path $PSScriptRoot '../ip-1/run.ps1') `
    -NginxExe $NginxExe -JavaExe $JavaExe `
    -NginxConfig (Join-Path $PSScriptRoot 'nginx.conf') `
    -RuntimeDirectory (Join-Path $PSScriptRoot 'runtime') `
    -ObservationScript $observations -AdditionalNginxPorts @(18082)
