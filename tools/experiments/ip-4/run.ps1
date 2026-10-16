param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$observations = {
    function Invoke-Case([string]$Title, [string]$Url, [bool]$Spoof, [string]$ExpectedXff) {
        Write-Output $Title
        $curlArguments = @('--silent', '--show-error', '--fail', '--max-time', '5', '--noproxy', '*')
        if ($Spoof) {
            $curlArguments += @('--header', 'X-Forwarded-For: 203.0.113.7')
        }
        $curlArguments += $Url
        $responseLines = & curl.exe @curlArguments
        if ($LASTEXITCODE -ne 0) { throw "Observation request failed: $Title" }
        $responseText = $responseLines -join "`n"
        Write-Output $responseText
        if ($responseText -notmatch '(?m)^peer=127\.0\.0\.1:[0-9]+$' -or
                $responseText -notmatch '(?m)^local=127\.0\.0\.1:18081$') {
            throw "Unexpected connection endpoints: $Title"
        }
        $expectedLine = [regex]::Escape('xForwardedFor=' + $ExpectedXff)
        if ($responseText -notmatch ('(?m)^' + $expectedLine + '$')) {
            throw "Unexpected XFF: $Title"
        }
        Write-Output 'Connection endpoints and XFF match the prediction.'
    }

    Invoke-Case '1. Normal request through Nginx (no client XFF)' `
        'http://[::1]:18080/peer' $false '::1'
    Invoke-Case '2. Spoofed XFF through Nginx (overwritten)' `
        'http://[::1]:18080/peer' $true '::1'
    Invoke-Case '3. Normal request directly to Java' `
        'http://127.0.0.1:18081/peer' $false '<absent>'
    Invoke-Case '4. Spoofed XFF directly to Java (bypasses Nginx)' `
        'http://127.0.0.1:18081/peer' $true '203.0.113.7'

    # These variables belong to the shared runner and identify only this experiment instance.
    Write-Output 'Stopping only the experiment Nginx; Java probe stays running for the next control.'
    & $NginxExe -p $nginxPrefix -c $experimentNginxConfig -s quit
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the experiment Nginx.' }
    if (!$nginxProcess.WaitForExit(5000)) { throw 'Experiment Nginx did not exit in time.' }
    if (Test-Listener '127.0.0.1' 18080) { throw 'Experiment Nginx port is still listening.' }

    Invoke-Case '5. Spoofed XFF directly to Java after Nginx stopped' `
        'http://127.0.0.1:18081/peer' $true '203.0.113.7'
}

& (Join-Path $PSScriptRoot '../ip-1/run.ps1') `
    -NginxExe $NginxExe -JavaExe $JavaExe `
    -NginxConfig (Join-Path $PSScriptRoot '../ip-2/nginx.conf') `
    -RuntimeDirectory (Join-Path $PSScriptRoot 'runtime') `
    -ObservationScript $observations
