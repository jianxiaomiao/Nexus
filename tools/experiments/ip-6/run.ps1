param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$observations = {
    $testChain = '203.0.113.7, 192.0.2.10, 198.51.100.20'

    function Invoke-Case([string]$Title, [string]$Url, [string]$ExpectedIp) {
        Write-Output $Title
        $responseLines = & curl.exe --silent --show-error --fail --max-time 5 --noproxy '*' `
            --header ("X-Forwarded-For: " + $testChain) $Url
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
        Write-Output 'Effective address forwarded to Java matches the prediction.'
    }

    Invoke-Case '1. Trusted IPv4 peer, recursive narrow trust' `
        'http://127.0.0.1:18080/recursive-narrow' '192.0.2.10'
    Invoke-Case '2. Same chain, recursive expanded trust' `
        'http://127.0.0.1:18080/recursive-expanded' '203.0.113.7'
    Invoke-Case '3. Same trusted peer and chain, recursion disabled' `
        'http://127.0.0.1:18080/non-recursive' '198.51.100.20'
    Invoke-Case '4. Untrusted IPv6 direct peer supplies the same chain' `
        'http://[::1]:18080/untrusted-peer' '::1'

    Write-Output 'Nginx evidence (original peer, received chain, effective address):'
    $logLines = Get-Content -LiteralPath (Join-Path $runtimePath 'logs/access.log') -Tail 4
    $logLines | Write-Output

    $expectations = @(
        @{ Route = '/recursive-narrow'; Peer = '127\.0\.0\.1'; Effective = '192\.0\.2\.10' },
        @{ Route = '/recursive-expanded'; Peer = '127\.0\.0\.1'; Effective = '203\.0\.113\.7' },
        @{ Route = '/non-recursive'; Peer = '127\.0\.0\.1'; Effective = '198\.51\.100\.20' },
        @{ Route = '/untrusted-peer'; Peer = '::1'; Effective = '::1' }
    )
    foreach ($expected in $expectations) {
        if (!($logLines | Where-Object {
            $_ -match ("connectionPeer=\[" + $expected.Peer + "\]:[0-9]+ ") -and
            $_ -match ("effectiveIp=" + $expected.Effective + " ") -and
            $_ -match ("route=" + [regex]::Escape($expected.Route) + " ") -and
            $_ -match 'status=200$'
        })) {
            throw "Missing Nginx evidence for route $($expected.Route)."
        }
    }
}

& (Join-Path $PSScriptRoot '../ip-1/run.ps1') `
    -NginxExe $NginxExe -JavaExe $JavaExe `
    -NginxConfig (Join-Path $PSScriptRoot 'nginx.conf') `
    -RuntimeDirectory (Join-Path $PSScriptRoot 'runtime') `
    -ObservationScript $observations
