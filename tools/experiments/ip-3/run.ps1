param(
    [string]$NginxExe = 'D:\nginx\nginx-1.31.6\nginx.exe',
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'
$observations = {
    function Invoke-Case([string]$Title, [string]$Url, [bool]$Spoof, [hashtable]$Expected) {
        Write-Output $Title
        $curlArguments = @('--silent', '--show-error', '--fail', '--max-time', '5', '--noproxy', '*')
        if ($Spoof) {
            $curlArguments += @('--header', 'X-Real-IP: 192.0.2.7',
                '--header', 'X-Forwarded-For: 203.0.113.7, 198.51.100.9',
                '--header', 'Forwarded: for=192.0.2.7')
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
        foreach ($field in $Expected.Keys) {
            $expectedLine = [regex]::Escape($field + '=' + $Expected[$field])
            if ($responseText -notmatch ('(?m)^' + $expectedLine + '$')) {
                throw "Unexpected $field in case: $Title"
            }
        }
        Write-Output 'Observed endpoints and header values match the prediction.'
    }

    Invoke-Case '1. Direct Java with spoofed headers (control)' `
        'http://127.0.0.1:18081/peer' $true @{
            xRealIp = '192.0.2.7'
            xForwardedFor = '203.0.113.7, 198.51.100.9'
            forwarded = 'for=192.0.2.7'
        }
    Invoke-Case '2. IPv6 -> Nginx overwrite -> Java, with spoofed headers' `
        'http://[::1]:18080/overwrite' $true @{
            xRealIp = '::1'
            xForwardedFor = '::1'
            forwarded = 'for=192.0.2.7'
        }
    Invoke-Case '3. IPv6 -> Nginx append -> Java, with the same spoofed headers' `
        'http://[::1]:18080/append' $true @{
            xRealIp = '::1'
            xForwardedFor = '203.0.113.7, 198.51.100.9, ::1'
            forwarded = 'for=192.0.2.7'
        }
    Invoke-Case '4. IPv6 -> Nginx append -> Java, without spoofed headers (control)' `
        'http://[::1]:18080/append' $false @{
            xRealIp = '::1'
            xForwardedFor = '::1'
            forwarded = '<absent>'
        }
}

& (Join-Path $PSScriptRoot '../ip-1/run.ps1') `
    -NginxExe $NginxExe -JavaExe $JavaExe `
    -NginxConfig (Join-Path $PSScriptRoot 'nginx.conf') `
    -RuntimeDirectory (Join-Path $PSScriptRoot 'runtime') `
    -ObservationScript $observations
