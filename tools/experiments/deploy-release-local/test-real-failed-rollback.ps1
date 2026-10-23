param(
    [string]$DeployRoot = 'D:\NexusDeploy',
    [string]$GoodReleaseName = 'V1.0.0__initial-deployment',
    [string]$CandidateReleaseName = 'V1.0.1__failed-startup-drill',
    [ValidateRange(1024, 65535)]
    [int]$BackendPort = 18081
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$acceptanceScript = Join-Path $PSScriptRoot 'accept-real-release.ps1'
$deployRootPath = [IO.Path]::GetFullPath($DeployRoot)
$releaseRoot = Join-Path $deployRootPath 'releases'
$goodReleasePath = Join-Path $releaseRoot $GoodReleaseName
$candidateReleasePath = Join-Path $releaseRoot $CandidateReleaseName
$stagingPath = Join-Path $releaseRoot ".staging-$CandidateReleaseName-$PID"
$currentPath = Join-Path $deployRootPath 'current'
$lastSuccessfulPath = Join-Path $deployRootPath 'state\last-successful.txt'
$rollbackReleasePath = Join-Path $deployRootPath 'state\rollback-release.txt'
$browserRoot = Join-Path $deployRootPath 'browsers\playwright-1.63.0'

function Assert-PathUnderRoot([string]$Path, [string]$Root) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd(
        [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($fullRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes expected root: $fullPath"
    }
}

function Get-CurrentTarget {
    $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction Stop
    if ($item.LinkType -ne 'Junction') {
        throw 'The current path is not a directory junction.'
    }
    return [IO.Path]::GetFullPath(@($item.Target)[0]).TrimEnd('\')
}

function Get-OptionalState([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return (Get-Content -LiteralPath $Path -Raw).Trim()
}

function Get-FileFingerprint([string]$Root) {
    return @(Get-ChildItem -LiteralPath $Root -File -Recurse |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                Path = $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
                Length = $_.Length
                Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        })
}

function Assert-FingerprintsEqual($Expected, $Actual, [string]$Label) {
    $expectedJson = $Expected | ConvertTo-Json -Depth 4 -Compress
    $actualJson = $Actual | ConvertTo-Json -Depth 4 -Compress
    if ($expectedJson -ne $actualJson) {
        throw "$Label fingerprint changed."
    }
}

function Get-FixedBrowserProcessCount {
    $rootPrefix = [IO.Path]::GetFullPath($browserRoot).TrimEnd('\') + '\'
    return @(Get-Process -Name 'chrome', 'chrome-headless-shell' `
            -ErrorAction SilentlyContinue | Where-Object {
            try {
                $_.Path -and [IO.Path]::GetFullPath($_.Path).StartsWith(
                    $rootPrefix, [StringComparison]::OrdinalIgnoreCase)
            } catch { $false }
        }).Count
}

foreach ($path in @($goodReleasePath, $candidateReleasePath, $stagingPath)) {
    Assert-PathUnderRoot $path $releaseRoot
}
if (-not (Test-Path -LiteralPath $goodReleasePath -PathType Container)) {
    throw "Known-good release does not exist: $goodReleasePath"
}
if (Test-Path -LiteralPath $candidateReleasePath) {
    throw "Candidate release already exists and will not be overwritten: $candidateReleasePath"
}
if (Test-Path -LiteralPath $stagingPath) {
    throw "Unexpected staging path already exists: $stagingPath"
}
if (Get-NetTCPConnection -State Listen -LocalPort $BackendPort `
        -ErrorAction SilentlyContinue) {
    throw "Port $BackendPort is already in use."
}

$goodReleaseFullPath = [IO.Path]::GetFullPath($goodReleasePath).TrimEnd('\')
if ((Get-CurrentTarget) -ne $goodReleaseFullPath) {
    throw 'Preflight failed: current does not point to the known-good release.'
}
$lastSuccessfulBefore = Get-OptionalState $lastSuccessfulPath
$rollbackBefore = Get-OptionalState $rollbackReleasePath
if ($lastSuccessfulBefore -ne $GoodReleaseName) {
    throw "Preflight failed: last-successful is $lastSuccessfulBefore."
}
$goodFingerprintBefore = Get-FileFingerprint $goodReleasePath

try {
    Copy-Item -LiteralPath $goodReleasePath -Destination $stagingPath -Recurse
    $manifestPath = Join-Path $stagingPath 'release-info.json'
    (Get-Item -LiteralPath $manifestPath).IsReadOnly = $false
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $manifest.version = $CandidateReleaseName
    [IO.File]::WriteAllText(
        $manifestPath,
        (($manifest | ConvertTo-Json -Depth 8) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Get-ChildItem -LiteralPath $stagingPath -File -Recurse |
        ForEach-Object { $_.IsReadOnly = $true }
    Move-Item -LiteralPath $stagingPath -Destination $candidateReleasePath
} finally {
    if (Test-Path -LiteralPath $stagingPath) {
        Remove-Item -LiteralPath $stagingPath -Recurse -Force
    }
}

$candidateWritableFiles = @(Get-ChildItem -LiteralPath $candidateReleasePath `
    -File -Recurse | Where-Object { -not $_.IsReadOnly })
if ($candidateWritableFiles.Count -ne 0) {
    throw "Candidate has writable files: $($candidateWritableFiles.Count)"
}
Write-Output "Candidate created: $CandidateReleaseName; files are read-only."

$powershellExe = (Get-Process -Id $PID).Path
$childOutput = @(& $powershellExe -NoProfile -File $acceptanceScript `
        -DeployRoot $deployRootPath `
        -ReleaseName $CandidateReleaseName `
        -BackendPort $BackendPort `
        -InjectMissingConfigFailure 2>&1 | ForEach-Object { "$_" })
$childExitCode = $LASTEXITCODE
$childOutput | Write-Output

if ($childExitCode -eq 0) {
    throw 'The intentionally broken candidate unexpectedly passed acceptance.'
}
$joinedChildOutput = $childOutput -join "`n"
if ($joinedChildOutput -notmatch
    'Candidate Java process exited before opening port') {
    throw 'The candidate failed for an unexpected reason.'
}
if ($joinedChildOutput -notmatch
    'FAIL: candidate was not promoted') {
    throw 'Acceptance did not report the rollback path.'
}

$logMatch = [regex]::Match(
    $joinedChildOutput,
    'FAIL: candidate was not promoted\. Inspect (?<path>[^\r\n]+)')
if (-not $logMatch.Success) {
    throw 'Acceptance output did not identify the candidate log directory.'
}
$candidateLogPath = [IO.Path]::GetFullPath($logMatch.Groups['path'].Value.Trim())
Assert-PathUnderRoot $candidateLogPath (Join-Path $deployRootPath 'logs\deploy')
$standardOutputPath = Join-Path $candidateLogPath 'backend-output.log'
$standardErrorPath = Join-Path $candidateLogPath 'backend-error.log'
if (-not (Test-Path -LiteralPath $standardOutputPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $standardErrorPath -PathType Leaf)) {
    throw 'Candidate startup logs are incomplete.'
}
$startupOutput = (Get-Content -LiteralPath $standardOutputPath -Raw) + "`n" +
    (Get-Content -LiteralPath $standardErrorPath -Raw)
if ($startupOutput -notmatch 'intentionally-missing\.yml' -or
    $startupOutput -notmatch 'does not exist') {
    throw 'Startup log did not prove the intentional missing-config failure.'
}

if ((Get-CurrentTarget) -ne $goodReleaseFullPath) {
    throw 'Rollback failed: current does not point back to the known-good release.'
}
if ((Get-OptionalState $lastSuccessfulPath) -ne $lastSuccessfulBefore) {
    throw 'Rollback failed: last-successful changed after candidate failure.'
}
if ((Get-OptionalState $rollbackReleasePath) -ne $rollbackBefore) {
    throw 'Rollback failed: rollback-release state changed after candidate failure.'
}
if (Get-NetTCPConnection -State Listen -LocalPort $BackendPort `
        -ErrorAction SilentlyContinue) {
    throw "Cleanup failed: port $BackendPort still has a listener."
}
if ((Get-FixedBrowserProcessCount) -ne 0) {
    throw 'Cleanup failed: a fixed-directory browser process remains.'
}
Assert-FingerprintsEqual $goodFingerprintBefore `
    (Get-FileFingerprint $goodReleasePath) 'Known-good release'

Write-Output "Failure evidence: required config missing; Java exited before opening $BackendPort."
Write-Output "Rollback: current=$GoodReleaseName lastSuccessful=$lastSuccessfulBefore rollback=$(if ($null -eq $rollbackBefore) { '<none>' } else { $rollbackBefore })"
Write-Output "Cleanup: port${BackendPort}Listeners=0 fixedBrowserProcesses=0"
Write-Output "PASS: real failed candidate rolled back without advancing successful state. Logs=$candidateLogPath"
