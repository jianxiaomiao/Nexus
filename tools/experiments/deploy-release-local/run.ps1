$ErrorActionPreference = 'Stop'

$runtimeParent = Join-Path $PSScriptRoot 'runtime'
$runId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'),
    ([Guid]::NewGuid().ToString('N').Substring(0, 8))
$deployRoot = Join-Path $runtimeParent "NexusDeploy-$runId"
$releaseRoot = Join-Path $deployRoot 'releases'
$configRoot = Join-Path $deployRoot 'config'
$configHistoryRoot = Join-Path $configRoot 'history'
$stateRoot = Join-Path $deployRoot 'state'
$logRoot = Join-Path $deployRoot 'logs'
$browserRoot = Join-Path $deployRoot 'browsers\playwright-1.63.0'
$currentPath = Join-Path $deployRoot 'current'
$deployLog = Join-Path $logRoot 'deploy\deploy.log'
$lastSuccessfulFile = Join-Path $stateRoot 'last-successful.txt'
$rollbackReleaseFile = Join-Path $stateRoot 'rollback-release.txt'

function Write-Utf8Text([string]$Path, [string]$Value) {
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Assert-PathUnderRoot([string]$Path, [string]$Root) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd(
        [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($fullRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes experiment root: $fullPath"
    }
}

function Get-ReleasePath([string]$ReleaseName) {
    if ($ReleaseName -notmatch '^V[0-9]+(?:\.[0-9]+)*__[a-z0-9-]+$') {
        throw "Invalid release name: $ReleaseName"
    }
    $path = Join-Path $releaseRoot $ReleaseName
    Assert-PathUnderRoot $path $deployRoot
    return $path
}

function Get-ArtifactHash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Save-ConfigSnapshot([string]$ReleaseName) {
    $snapshotRoot = Join-Path $configHistoryRoot $ReleaseName
    Assert-PathUnderRoot $snapshotRoot $deployRoot
    if (Test-Path -LiteralPath $snapshotRoot) {
        throw "Configuration snapshot already exists: $ReleaseName"
    }

    New-Item -ItemType Directory -Path $snapshotRoot | Out-Null
    $sourcePath = Join-Path $configRoot 'application-prod.yml'
    $snapshotPath = Join-Path $snapshotRoot 'application-prod.yml'
    [IO.File]::WriteAllBytes($snapshotPath, [IO.File]::ReadAllBytes($sourcePath))
    (Get-Item -LiteralPath $snapshotPath).IsReadOnly = $true
}

function New-ExperimentRelease(
    [string]$ReleaseName,
    [ValidateSet('PASS', 'FAIL')][string]$HealthStatus
) {
    $releasePath = Get-ReleasePath $ReleaseName
    if (Test-Path -LiteralPath $releasePath) {
        throw "Release already exists and will not be overwritten: $ReleaseName"
    }

    $stagingRoot = Join-Path $deployRoot 'staging'
    $stagingPath = Join-Path $stagingRoot (
        '{0}-{1}' -f $ReleaseName, [Guid]::NewGuid().ToString('N'))
    Assert-PathUnderRoot $stagingPath $deployRoot
    New-Item -ItemType Directory -Path $stagingPath | Out-Null

    $jarPath = Join-Path $stagingPath 'server\nexus-server.jar'
    $indexPath = Join-Path $stagingPath 'console\dist\index.html'
    $healthPath = Join-Path $stagingPath 'health-status.txt'
    Write-Utf8Text $jarPath "fake JAR for $ReleaseName`n"
    Write-Utf8Text $indexPath "<!doctype html><title>$ReleaseName</title>`n"
    Write-Utf8Text $healthPath "$HealthStatus`n"

    $releaseInfo = [ordered]@{
        version = $ReleaseName
        createdAt = [DateTimeOffset]::Now.ToString('o')
        playwrightVersion = '1.63.0'
        artifacts = [ordered]@{
            'server/nexus-server.jar' = Get-ArtifactHash $jarPath
            'console/dist/index.html' = Get-ArtifactHash $indexPath
            'health-status.txt' = Get-ArtifactHash $healthPath
        }
    }
    Write-Utf8Text (Join-Path $stagingPath 'release-info.json') (
        ($releaseInfo | ConvertTo-Json -Depth 4) + "`n")

    Assert-PathUnderRoot $releasePath $deployRoot
    Move-Item -LiteralPath $stagingPath -Destination $releasePath

    Get-ChildItem -LiteralPath $releasePath -File -Recurse |
        ForEach-Object { $_.IsReadOnly = $true }

    Add-Content -LiteralPath $deployLog -Encoding utf8 `
        -Value "PUBLISHED $ReleaseName health=$HealthStatus"
    return $releasePath
}

function Assert-ReleaseIntegrity([string]$ReleaseName) {
    $releasePath = Get-ReleasePath $ReleaseName
    $infoPath = Join-Path $releasePath 'release-info.json'
    $info = Get-Content -LiteralPath $infoPath -Raw | ConvertFrom-Json
    if ($info.version -ne $ReleaseName) {
        throw "Release metadata version mismatch: $ReleaseName"
    }

    foreach ($artifact in $info.artifacts.PSObject.Properties) {
        $artifactPath = Join-Path $releasePath (
            $artifact.Name.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
            throw "Release artifact is missing: $($artifact.Name)"
        }
        if ((Get-ArtifactHash $artifactPath) -ne $artifact.Value) {
            throw "Release artifact hash changed: $($artifact.Name)"
        }
        if (-not (Get-Item -LiteralPath $artifactPath).IsReadOnly) {
            throw "Release artifact is writable: $($artifact.Name)"
        }
    }

    $writableReleaseFiles = @(Get-ChildItem -LiteralPath $releasePath `
            -File -Recurse | Where-Object { -not $_.IsReadOnly })
    if ($writableReleaseFiles.Count -ne 0) {
        throw "Release contains writable files: $ReleaseName"
    }
}

function Set-CurrentRelease([string]$ReleaseName) {
    $releasePath = Get-ReleasePath $ReleaseName
    if (-not (Test-Path -LiteralPath $releasePath -PathType Container)) {
        throw "Release does not exist: $ReleaseName"
    }

    $existing = Get-Item -LiteralPath $currentPath -Force `
        -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        if ($existing.LinkType -ne 'Junction') {
            throw 'The experiment current path exists but is not a junction.'
        }
        if ([IO.Path]::GetFullPath($existing.FullName) -ne
            [IO.Path]::GetFullPath($currentPath)) {
            throw 'The current junction path did not resolve to the expected location.'
        }
        Remove-Item -LiteralPath $currentPath -Force
    }

    New-Item -ItemType Junction -Path $currentPath `
        -Target $releasePath | Out-Null
}

function Get-CurrentReleaseName {
    $current = Get-Item -LiteralPath $currentPath -Force
    if ($current.LinkType -ne 'Junction') {
        throw 'Current is not a junction.'
    }
    $target = @($current.Target)[0]
    return Split-Path -Leaf $target
}

function Invoke-ReleaseDeployment([string]$ReleaseName) {
    Assert-ReleaseIntegrity $ReleaseName
    $lastSuccessful = if (Test-Path -LiteralPath $lastSuccessfulFile) {
        (Get-Content -LiteralPath $lastSuccessfulFile -Raw).Trim()
    } else {
        $null
    }

    Save-ConfigSnapshot $ReleaseName
    Set-CurrentRelease $ReleaseName
    Add-Content -LiteralPath $deployLog -Encoding utf8 `
        -Value "ACTIVATED-CANDIDATE $ReleaseName"

    $healthStatus = (Get-Content -LiteralPath (
            Join-Path $currentPath 'health-status.txt') -Raw).Trim()
    if ($healthStatus -ne 'PASS') {
        Add-Content -LiteralPath $deployLog -Encoding utf8 `
            -Value "HEALTH-FAILED $ReleaseName"
        if ([string]::IsNullOrWhiteSpace($lastSuccessful)) {
            throw "First release failed health check: $ReleaseName"
        }
        Set-CurrentRelease $lastSuccessful
        Add-Content -LiteralPath $deployLog -Encoding utf8 `
            -Value "ROLLED-BACK $ReleaseName to=$lastSuccessful"
        return $false
    }

    if (-not [string]::IsNullOrWhiteSpace($lastSuccessful) -and
        $lastSuccessful -ne $ReleaseName) {
        Write-Utf8Text $rollbackReleaseFile "$lastSuccessful`n"
    }
    Write-Utf8Text $lastSuccessfulFile "$ReleaseName`n"
    Add-Content -LiteralPath $deployLog -Encoding utf8 `
        -Value "HEALTH-PASSED $ReleaseName"
    return $true
}

New-Item -ItemType Directory -Path $releaseRoot, $configRoot,
    $configHistoryRoot, $stateRoot,
    (Join-Path $logRoot 'java'), (Join-Path $logRoot 'nginx'),
    (Join-Path $logRoot 'deploy'), $browserRoot -Force | Out-Null

Write-Utf8Text (Join-Path $configRoot 'application-prod.yml') @'
# Experiment only; no secret values.
server:
  address: 127.0.0.1
  port: 8080
'@
Write-Utf8Text (Join-Path $browserRoot 'README.txt') `
    "Fixed browser directory placeholder for Playwright 1.63.0.`n"
Write-Utf8Text (Join-Path $stateRoot 'startup-environment.txt') `
    "PLAYWRIGHT_BROWSERS_PATH=$browserRoot`n"

$configHashBefore = Get-ArtifactHash (
    Join-Path $configRoot 'application-prod.yml')
$browserHashBefore = Get-ArtifactHash (
    Join-Path $browserRoot 'README.txt')

$v1 = 'V1.0.0__initial-deployment'
$v2 = 'V1.1.0__console-update'
$v3 = 'V1.1.1__broken-candidate'
New-ExperimentRelease $v1 PASS | Out-Null
New-ExperimentRelease $v2 PASS | Out-Null
New-ExperimentRelease $v3 FAIL | Out-Null

if (-not (Invoke-ReleaseDeployment $v1)) {
    throw 'V1 should have passed its health check.'
}
if (-not (Invoke-ReleaseDeployment $v2)) {
    throw 'V2 should have passed its health check.'
}

$duplicateRejected = $false
try {
    New-ExperimentRelease $v2 PASS | Out-Null
} catch {
    if ($_.Exception.Message -like 'Release already exists*') {
        $duplicateRejected = $true
        Add-Content -LiteralPath $deployLog -Encoding utf8 `
            -Value "DUPLICATE-REJECTED $v2"
    } else {
        throw
    }
}
if (-not $duplicateRejected) {
    throw 'Publishing the same release name twice was not rejected.'
}

if (Invoke-ReleaseDeployment $v3) {
    throw 'The broken V3 candidate unexpectedly passed health checking.'
}

foreach ($release in @($v1, $v2, $v3)) {
    Assert-ReleaseIntegrity $release
    $releasePath = Get-ReleasePath $release
    if ((Test-Path -LiteralPath (Join-Path $releasePath 'config')) -or
        (Test-Path -LiteralPath (Join-Path $releasePath 'logs'))) {
        throw "Mutable config or logs appeared inside release: $release"
    }

    $configSnapshot = Join-Path $configHistoryRoot `
        "$release\application-prod.yml"
    if (-not (Test-Path -LiteralPath $configSnapshot -PathType Leaf) -or
        (Get-ArtifactHash $configSnapshot) -ne $configHashBefore -or
        -not (Get-Item -LiteralPath $configSnapshot).IsReadOnly) {
        throw "The external configuration snapshot is invalid: $release"
    }
}

$actualCurrent = Get-CurrentReleaseName
$lastSuccessful = (Get-Content -LiteralPath $lastSuccessfulFile -Raw).Trim()
$rollbackRelease = (Get-Content -LiteralPath $rollbackReleaseFile -Raw).Trim()
if ($actualCurrent -ne $v2 -or $lastSuccessful -ne $v2 -or
    $rollbackRelease -ne $v1) {
    throw "Unexpected final state: current=$actualCurrent " +
        "lastSuccessful=$lastSuccessful rollback=$rollbackRelease"
}

if ((Get-ArtifactHash (Join-Path $configRoot 'application-prod.yml')) -ne
    $configHashBefore) {
    throw 'Shared production configuration changed during release switching.'
}
if ((Get-ArtifactHash (Join-Path $browserRoot 'README.txt')) -ne
    $browserHashBefore) {
    throw 'Fixed browser directory changed during release switching.'
}

$v2Jar = Join-Path (Get-ReleasePath $v2) 'server\nexus-server.jar'
$readOnlyWriteRejected = $false
try {
    Add-Content -LiteralPath $v2Jar -Value 'unexpected mutation'
} catch {
    $readOnlyWriteRejected = $true
}
if (-not $readOnlyWriteRejected) {
    throw 'A published release artifact could still be written in place.'
}
Assert-ReleaseIntegrity $v2

Write-Output "Experiment root: $deployRoot"
Write-Output "Current junction: current -> $actualCurrent"
Write-Output "Last successful: $lastSuccessful"
Write-Output "Rollback release: $rollbackRelease"
Write-Output "Duplicate release rejected: $duplicateRejected"
Write-Output "Read-only mutation rejected: $readOnlyWriteRejected"
Write-Output "External config: $(Join-Path $configRoot 'application-prod.yml')"
Write-Output "External logs: $logRoot"
Write-Output "Fixed browser path: $browserRoot"
Write-Output 'PASS: versioned release switching, failed-candidate rollback, integrity, and mutable-directory boundaries are correct.'
