param(
    [Parameter(Mandatory = $true)]
    [string]$NginxPath,

    [Parameter(Mandatory = $true)]
    [string]$PrefixPath,

    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedOwner,

    [Parameter(Mandatory = $true)]
    [string]$AuditPath
)

$ErrorActionPreference = 'Stop'

$expectedExecutablePath = [IO.Path]::GetFullPath($NginxPath)
$expectedPrefixPath = ([IO.Path]::GetFullPath($PrefixPath)).TrimEnd('\', '/')
$nginxConfigPath = [IO.Path]::GetFullPath($ConfigPath)
$auditPath = [IO.Path]::GetFullPath($AuditPath)
$prefixArgument = $expectedPrefixPath.Replace('\', '/') + '/'
$configArgument = $nginxConfigPath.Replace('\', '/')
$bootstrapErrorArgument = (Join-Path (Split-Path $auditPath -Parent) `
    'nginx-bootstrap-error.log').Replace('\', '/')

function Test-ContainsOrdinalIgnoreCase([string]$Value, [string]$Expected) {
    return $null -ne $Value -and
        $Value.IndexOf($Expected, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Get-ProcessOwner(
    [Microsoft.Management.Infrastructure.CimInstance]$Process) {
    $owner = Invoke-CimMethod -InputObject $Process -MethodName GetOwner
    if ($owner.ReturnValue -ne 0 -or [string]::IsNullOrWhiteSpace($owner.User)) {
        throw "Cannot resolve owner for Nginx PID $($Process.ProcessId)."
    }
    return "$($owner.Domain)\$($owner.User)"
}

function Get-RelevantNginxProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" |
        Where-Object {
            [string]::Equals($_.ExecutablePath, $expectedExecutablePath,
                [StringComparison]::OrdinalIgnoreCase) -or
            (Test-ContainsOrdinalIgnoreCase $_.CommandLine $prefixArgument) -or
            (Test-ContainsOrdinalIgnoreCase $_.CommandLine $expectedPrefixPath)
        })
}

$candidates = @(Get-RelevantNginxProcesses)
$approved = @()
foreach ($candidate in $candidates) {
    if (-not [string]::Equals($candidate.ExecutablePath,
            $expectedExecutablePath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup: PID $($candidate.ProcessId) has an unexpected executable path."
    }

    $prefixPattern = '(?i)(?:^|\s)-p\s+"?' +
        [regex]::Escape($prefixArgument) + '"?(?=\s|$)'
    if ($null -eq $candidate.CommandLine -or
        -not [regex]::IsMatch($candidate.CommandLine, $prefixPattern)) {
        throw "Refusing cleanup: PID $($candidate.ProcessId) lacks the exact -p prefix."
    }

    $owner = Get-ProcessOwner $candidate
    if (-not [string]::Equals($owner, $ExpectedOwner,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup: PID $($candidate.ProcessId) is owned by $owner."
    }
    $approved += $candidate
}

$approvedPids = @($approved | ForEach-Object { [int]$_.ProcessId })
foreach ($processId in $approvedPids) {
    Stop-Process -Id $processId -Force -ErrorAction Stop
}

$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $remaining = @(Get-RelevantNginxProcesses)
    if ($remaining.Count -eq 0) { break }
    Start-Sleep -Milliseconds 200
} while ([DateTime]::UtcNow -lt $deadline)

if ($remaining.Count -ne 0) {
    $remainingPids = ($remaining.ProcessId | Sort-Object) -join ','
    throw "Refusing new Nginx start: matching old PIDs remain: $remainingPids"
}

$action = if ($approvedPids.Count -eq 0) { 'none' } else { 'killed' }
$pidText = ($approvedPids | Sort-Object) -join ','
$auditLine = 'at={0} action={1} pids={2}' -f `
    [DateTimeOffset]::Now.ToString('o'), $action, $pidText
Add-Content -LiteralPath $auditPath -Value $auditLine -Encoding utf8

& $expectedExecutablePath -p $prefixArgument -c $configArgument `
    -e $bootstrapErrorArgument -g 'daemon off;'
exit $LASTEXITCODE
