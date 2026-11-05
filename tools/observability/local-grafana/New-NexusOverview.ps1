[CmdletBinding()]
param(
    [string]$DataSourceUid = 'fg3cm3bqo1o1sa',
    [string]$OutputPath = (Join-Path $PSScriptRoot 'nexus-local-overview-v2.json')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (Test-Path -LiteralPath $OutputPath) {
    throw "Output already exists; choose a new OutputPath: $OutputPath"
}

$source = @{ type = 'prometheus'; uid = $DataSourceUid }
$panels = [System.Collections.Generic.List[object]]::new()

function Add-Chart {
    param(
        [int]$Id, [string]$Title, [string]$Description,
        [string]$Unit, [array]$Queries,
        [Nullable[double]]$SoftMax = $null
    )

    $targets = @(
        $index = 0
        foreach ($query in $Queries) {
            @{
                refId = [string][char](65 + $index)
                datasource = $source
                expr = $query.Expression
                legendFormat = $query.Legend
                range = $true
                instant = $false
                interval = '15s'
                editorMode = 'code'
            }
            $index++
        }
    )

    $axis = @{ drawStyle = 'line'; lineWidth = 1; fillOpacity = 10; spanNulls = $false; showPoints = 'auto' }
    if ($null -ne $SoftMax) {
        $axis.axisSoftMin = 0
        $axis.axisSoftMax = [double]$SoftMax
    }

    $panels.Add(@{
        id = $Id
        title = $Title
        description = $Description
        type = 'timeseries'
        datasource = $source
        gridPos = @{ x = $(if (($Id - 1) % 2 -eq 0) { 0 } else { 12 }); y = [math]::Floor(($Id - 1) / 2) * 8; w = 12; h = 8 }
        targets = $targets
        fieldConfig = @{
            defaults = @{
                unit = $Unit
                color = @{ mode = 'palette-classic' }
                custom = $axis
            }
            overrides = @()
        }
        options = @{
            legend = @{ showLegend = $true; displayMode = 'list'; placement = 'bottom' }
            tooltip = @{ mode = 'multi'; sort = 'none' }
        }
    })
}

function Q([string]$Expression, [string]$Legend) {
    return @{ Expression = $Expression; Legend = $Legend }
}

$job = '{job="nexus-local"}'
Add-Chart 1 'Nexus scrape status' '1 means the latest scrape succeeded; 0 means it failed. Missing series is not healthy.' 'short' @((Q "up$job" 'scrape status')) -SoftMax 1
Add-Chart 2 'JVM uptime' 'Nexus JVM uptime; resets when the process restarts.' 's' @((Q "process_uptime_seconds$job" 'uptime'))
Add-Chart 3 'HTTP request rate' 'Five-minute per-second average across observed HTTP series; includes all recorded status codes.' 'reqps' @((Q "sum(rate(http_server_requests_seconds_count${job}[5m]))" 'requests/s')) -SoftMax 0.01
Add-Chart 4 'HTTP requests by status' 'Five-minute average rate by HTTP status. 4xx and 429 are not automatically business failures; missing series is not zero.' 'reqps' @((Q 'sum by (status) (rate(http_server_requests_seconds_count{job="nexus-local"}[5m]))' '{{status}}')) -SoftMax 0.01
Add-Chart 5 'JVM heap memory' 'Heap used and committed; the separately reported maximum appears in its own panel.' 'bytes' @(
    (Q 'sum(jvm_memory_used_bytes{job="nexus-local",area="heap"})' 'used'),
    (Q 'sum(jvm_memory_committed_bytes{job="nexus-local",area="heap"})' 'committed')
)
Add-Chart 6 'CPU usage' 'Process is the Nexus JVM; system is the whole operating system. The JVM metric excludes separately running Chromium processes. Unavailable values remain unknown.' 'percentunit' @(
    (Q "process_cpu_usage$job" 'process'),
    (Q "system_cpu_usage$job" 'system')
)
Add-Chart 7 'Hikari connections' 'Active, idle and maximum connections per pool. Compare active against the matching pool maximum.' 'short' @(
    (Q "hikaricp_connections_active$job" 'active {{pool}}'),
    (Q "hikaricp_connections_idle$job" 'idle {{pool}}'),
    (Q "hikaricp_connections_max$job" 'max {{pool}}')
)
Add-Chart 8 'Hikari pending' 'Threads currently waiting for a database connection, by pool.' 'short' @((Q "hikaricp_connections_pending$job" 'pending {{pool}}')) -SoftMax 1
Add-Chart 9 'Rate limiter states and waiters' 'Current global Application state count and waiting requests; states are not active requests.' 'short' @(
    (Q "nexus_ratelimit_states$job" 'states'),
    (Q "nexus_ratelimit_waiters$job" 'waiters')
) -SoftMax 1
Add-Chart 10 'Rate limiter decisions' 'Five-minute rate of admission decisions by outcome; immediate, queued and rejected outcomes have different meanings.' 'reqps' @((Q 'sum by (outcome) (rate(nexus_ratelimit_admission_seconds_count{job="nexus-local"}[5m]))' '{{outcome}}')) -SoftMax 0.01
Add-Chart 11 'Screenshot unfinished jobs' 'Queued plus running screenshot jobs. Database read failure may produce NaN; missing or NaN is not zero.' 'short' @((Q "nexus_screenshot_jobs_unfinished$job" 'unfinished')) -SoftMax 1
Add-Chart 12 'Screenshot execution slot' 'Shared screenshot execution slot: 0 idle, 1 busy. Compare with unfinished jobs.' 'short' @((Q "nexus_screenshot_slot_busy$job" 'busy')) -SoftMax 1
Add-Chart 13 'Usage recording failures' 'Five-minute rate of final asynchronous Usage recording failures by reason. HTTP success does not guarantee Usage persistence.' 'ops' @((Q 'sum by (reason) (rate(nexus_usage_record_failures_total{job="nexus-local"}[5m]))' '{{reason}}')) -SoftMax 0.01
Add-Chart 14 'JVM heap reported max' 'Sum of positive per-pool reported heap maxima; this is not necessarily the exact JVM -Xmx limit.' 'bytes' @((Q 'sum(jvm_memory_max_bytes{job="nexus-local",area="heap"} > 0)' 'reported max'))

$dashboard = @{
    title = 'Nexus Local Overview - Generated V2'
    uid = $null
    tags = @('nexus', 'local')
    timezone = 'browser'
    schemaVersion = 41
    version = 0
    editable = $true
    refresh = '15s'
    time = @{ from = 'now-1h'; to = 'now' }
    panels = @($panels.ToArray())
}

$dashboard | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Host "Dashboard written to $OutputPath"
