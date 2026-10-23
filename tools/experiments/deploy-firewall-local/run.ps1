param(
    [ValidateRange(1024, 65535)]
    [int]$TestPort = 18090
)

$ErrorActionPreference = 'Stop'
$allowRuleName = "Nexus Experiment Firewall Allow $TestPort"
$blockRuleName = "Nexus Experiment Firewall Block $TestPort"
$ruleNames = @($allowRuleName, $blockRuleName)
$serverJob = $null
$passed = $false

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-ListenerSnapshot([int[]]$Ports) {
    $items = foreach ($port in $Ports) {
        Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            ForEach-Object {
                '{0}|{1}|{2}' -f $_.LocalAddress, $_.LocalPort, $_.OwningProcess
            }
    }
    return (@($items | Sort-Object) -join "`n")
}

function Wait-TestListener([int]$Port, [System.Management.Automation.Job]$Job) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Job.State -in @('Failed', 'Stopped', 'Completed')) {
            $reason = Receive-Job -Job $Job -Keep -ErrorAction SilentlyContinue | Out-String
            throw "Temporary listener stopped unexpectedly. $reason"
        }

        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 `
                -Uri "http://127.0.0.1:$Port/readiness"
            if ($response.StatusCode -eq 200 -and
                $response.Content -eq 'NEXUS FIREWALL EXPERIMENT ALLOW') {
                return
            }
        } catch {
        }
        Start-Sleep -Milliseconds 150
    }
    throw "Temporary listener did not open port $Port."
}

function Invoke-LoopbackProbe([int]$Port) {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 `
        -Uri "http://127.0.0.1:$Port/self-check"
    if ($response.StatusCode -ne 200 -or
        $response.Content -ne 'NEXUS FIREWALL EXPERIMENT ALLOW') {
        throw 'The temporary HTTP listener did not return the fixed response.'
    }
}

function Get-ObservedRequests([System.Management.Automation.Job]$Job) {
    return @(Receive-Job -Job $Job -Keep -ErrorAction SilentlyContinue |
        Where-Object { $_.Kind -eq 'HttpRequest' })
}

function Write-ObservedRequests($Requests, [string]$Phase) {
    Write-Output "$Phase requests observed by the temporary server:"
    foreach ($request in @($Requests)) {
        Write-Output ("  at={0} peer={1} request={2}" -f `
            $request.ObservedAt, $request.PeerAddress, $request.RequestLine)
    }
}

if (-not (Test-IsAdministrator)) {
    throw 'Run this experiment from a PowerShell window opened as Administrator.'
}

if (Get-NetTCPConnection -State Listen -LocalPort $TestPort -ErrorAction SilentlyContinue) {
    throw "Port $TestPort is already in use; no firewall rule was changed."
}

foreach ($ruleName in $ruleNames) {
    if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
        throw "Firewall rule already exists: $ruleName. Remove or inspect it before rerunning."
    }
}

$dailyBefore = Get-ListenerSnapshot @(80, 8080)
$profiles = Get-NetFirewallProfile |
    Select-Object Name, Enabled, DefaultInboundAction, DefaultOutboundAction
$addresses = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.AddressState -eq 'Preferred' -and
        $_.IPAddress -notmatch '^(127\.|169\.254\.|198\.18\.)'
    } |
    Sort-Object InterfaceIndex, IPAddress |
    Select-Object InterfaceAlias, IPAddress, PrefixLength

Write-Output 'Firewall profiles:'
$profiles | Format-Table -AutoSize | Out-String | Write-Output
Write-Output 'Candidate tablet URLs (try the hotspot/WLAN address):'
foreach ($address in $addresses) {
    Write-Output ("  {0,-28} http://{1}:{2}/allow" -f `
        $address.InterfaceAlias, $address.IPAddress, $TestPort)
}

try {
    $serverJob = Start-Job -ArgumentList $TestPort -ScriptBlock {
        param([int]$Port)

        $body = [Text.Encoding]::UTF8.GetBytes('NEXUS FIREWALL EXPERIMENT ALLOW')
        $headers = [Text.Encoding]::ASCII.GetBytes(
            "HTTP/1.1 200 OK`r`n" +
            "Content-Type: text/plain; charset=utf-8`r`n" +
            "Content-Length: $($body.Length)`r`n" +
            "Cache-Control: no-store`r`n" +
            "Connection: close`r`n`r`n")
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $Port)
        $listener.Start()
        try {
            while ($true) {
                $client = $listener.AcceptTcpClient()
                try {
                    $stream = $client.GetStream()
                    $requestBuffer = [byte[]]::new(4096)
                    $bytesRead = $stream.Read($requestBuffer, 0, $requestBuffer.Length)
                    if ($bytesRead -gt 0) {
                        $requestText = [Text.Encoding]::ASCII.GetString(
                            $requestBuffer, 0, $bytesRead)
                        $requestLine = ($requestText -split "`r?`n", 2)[0]
                        [pscustomobject]@{
                            Kind = 'HttpRequest'
                            ObservedAt = [DateTimeOffset]::Now.ToString('o')
                            PeerAddress = $client.Client.RemoteEndPoint.Address.ToString()
                            RequestLine = $requestLine
                        }
                        $stream.Write($headers, 0, $headers.Length)
                        $stream.Write($body, 0, $body.Length)
                        $stream.Flush()
                    }
                } catch [IO.IOException] {
                    # A readiness probe or browser may disconnect early; keep serving.
                } finally {
                    $client.Dispose()
                }
            }
        } finally {
            $listener.Stop()
        }
    }

    Wait-TestListener $TestPort $serverJob
    Invoke-LoopbackProbe $TestPort

    New-NetFirewallRule -DisplayName $allowRuleName -Direction Inbound `
        -Action Allow -Enabled True -Profile Any -Protocol TCP `
        -LocalPort $TestPort -RemoteAddress LocalSubnet | Out-Null

    Write-Output "ALLOW rule created: $allowRuleName"
    Write-Output 'On the tablet, open one candidate URL above and look for:'
    Write-Output '  NEXUS FIREWALL EXPERIMENT ALLOW'
    $allowObservation = (Read-Host 'Type PASS only if the tablet displayed that exact text').Trim()
    if ($allowObservation -ne 'PASS') {
        throw 'Allow phase was not confirmed; hotspot isolation/address selection must be diagnosed first.'
    }

    Start-Sleep -Milliseconds 250
    $allowRequests = Get-ObservedRequests $serverJob
    $externalAllowRequests = @($allowRequests | Where-Object {
            $_.PeerAddress -notin @('127.0.0.1', '::1')
        })
    Write-ObservedRequests $externalAllowRequests 'ALLOW'
    if ($externalAllowRequests.Count -eq 0) {
        throw 'The tablet confirmation was not backed by a non-loopback request at the server.'
    }

    Get-NetFirewallRule -DisplayName $allowRuleName -ErrorAction Stop |
        Remove-NetFirewallRule -ErrorAction Stop

    New-NetFirewallRule -DisplayName $blockRuleName -Direction Inbound `
        -Action Block -Enabled True -Profile Any -Protocol TCP `
        -LocalPort $TestPort -RemoteAddress Any | Out-Null

    Start-Sleep -Seconds 1
    $requestCountBeforeBlockProbe = (Get-ObservedRequests $serverJob).Count

    $cacheBreaker = [Guid]::NewGuid().ToString('N')
    Write-Output "BLOCK rule created: $blockRuleName"
    Write-Output 'On the tablet, open a fresh URL (do not trust the previous page):'
    foreach ($address in $addresses) {
        Write-Output ("  http://{0}:{1}/blocked-{2}" -f `
            $address.IPAddress, $TestPort, $cacheBreaker)
    }
    $blockObservation = (Read-Host 'Type PASS only if every matching tablet request now fails to connect').Trim()

    Start-Sleep -Milliseconds 250
    $requestsAfterBlockProbe = Get-ObservedRequests $serverJob
    $newBlockRequests = @($requestsAfterBlockProbe |
        Select-Object -Skip $requestCountBeforeBlockProbe)
    Write-ObservedRequests $newBlockRequests 'BLOCK'

    if ($newBlockRequests.Count -gt 0) {
        throw 'At least one request reached the listener after the Any-source Block rule was active.'
    }
    if ($blockObservation -ne 'PASS') {
        throw 'No blocked request reached the server; the tablet result may be a retained page or wrong URL.'
    }

    $passed = $true
} finally {
    foreach ($ruleName in $ruleNames) {
        Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
            Remove-NetFirewallRule -ErrorAction SilentlyContinue
    }
    if ($null -ne $serverJob) {
        Stop-Job -Job $serverJob -ErrorAction SilentlyContinue
        Remove-Job -Job $serverJob -Force -ErrorAction SilentlyContinue
    }

    Start-Sleep -Milliseconds 250
    $remainingRules = @(
        foreach ($ruleName in $ruleNames) {
            Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        })
    $remainingListener = @(
        Get-NetTCPConnection -State Listen -LocalPort $TestPort `
            -ErrorAction SilentlyContinue)
    $dailyAfter = Get-ListenerSnapshot @(80, 8080)

    Write-Output ("Cleanup: rulesRemaining={0} listenerRemaining={1} daily80And8080Unchanged={2}" -f `
        $remainingRules.Count, $remainingListener.Count, ($dailyBefore -ceq $dailyAfter))

    if ($remainingRules.Count -ne 0 -or $remainingListener.Count -ne 0 -or
        $dailyBefore -cne $dailyAfter) {
        throw 'Experiment cleanup verification failed; inspect firewall rules and listeners.'
    }
}

if ($passed) {
    Write-Output 'PASS: external tablet observed Windows Firewall allow then block; cleanup completed.'
}
