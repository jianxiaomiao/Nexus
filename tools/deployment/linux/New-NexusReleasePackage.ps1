[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^V[0-9]+(?:\.[0-9]+)*__[a-z0-9-]+$')]
    [string]$ReleaseName
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$outputRoot = Join-Path $repoRoot 'nexus-server\target\linux-release'
$releasePath = Join-Path $outputRoot $ReleaseName
$archivePath = Join-Path $outputRoot "$ReleaseName.tar.gz"
$verificationPath = Join-Path $outputRoot (
    '.verify-{0}' -f [Guid]::NewGuid().ToString('N'))
$jarSource = Join-Path $repoRoot `
    'nexus-server\target\nexus-server-0.0.1-SNAPSHOT.jar'
$consoleSource = Join-Path $repoRoot 'nexus-console\dist'

function Assert-PathUnderOutputRoot([string]$Path) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($outputRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith(
            $fullRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes release output root: $fullPath"
    }
}

function Get-RelativeArtifactPath([string]$Root, [string]$Path) {
    return [IO.Path]::GetRelativePath($Root, $Path).Replace('\', '/')
}

function Get-ArtifactRecords([string]$Root) {
    return @(
        Get-ChildItem -LiteralPath $Root -File -Recurse |
            Where-Object { $_.Name -ne 'release-info.json' } |
            ForEach-Object {
                [ordered]@{
                    path = Get-RelativeArtifactPath $Root $_.FullName
                    length = $_.Length
                    sha256 = (Get-FileHash -LiteralPath $_.FullName `
                            -Algorithm SHA256).Hash
                }
            } |
            Sort-Object path
    )
}

function Write-Utf8Text([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText(
        $Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Assert-ReleaseIntegrity([string]$Root) {
    $manifestPath = Join-Path $Root 'release-info.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw |
        ConvertFrom-Json

    if ($manifest.version -ne $ReleaseName) {
        throw "Manifest version mismatch: $($manifest.version)"
    }

    $expected = @{}
    foreach ($artifact in $manifest.artifacts) {
        if ($artifact.path -notmatch `
                '^(?:server/nexus-server\.jar|console/dist/.+)$' -or
            $artifact.path.Contains('..') -or
            $artifact.path.StartsWith('/')) {
            throw "Invalid artifact path in manifest: $($artifact.path)"
        }
        if ($expected.ContainsKey($artifact.path)) {
            throw "Duplicate artifact path in manifest: $($artifact.path)"
        }
        $expected[$artifact.path] = $artifact
    }

    $actualFiles = @(Get-ChildItem -LiteralPath $Root -File -Recurse)
    if ($actualFiles.Count -ne ($expected.Count + 1)) {
        throw 'Release contains a missing or unlisted file.'
    }

    foreach ($file in $actualFiles) {
        $relativePath = Get-RelativeArtifactPath $Root $file.FullName
        if ($relativePath -eq 'release-info.json') {
            continue
        }
        if (-not $expected.ContainsKey($relativePath)) {
            throw "Unlisted release file: $relativePath"
        }
        $record = $expected[$relativePath]
        if ($file.Length -ne [long]$record.length) {
            throw "Artifact length mismatch: $relativePath"
        }
        $actualHash = (Get-FileHash -LiteralPath $file.FullName `
                -Algorithm SHA256).Hash
        if ($actualHash -ne $record.sha256) {
            throw "Artifact SHA-256 mismatch: $relativePath"
        }
    }
}

if (-not (Test-Path -LiteralPath $jarSource -PathType Leaf)) {
    throw "Backend JAR is missing: $jarSource"
}
if (-not (Test-Path -LiteralPath `
        (Join-Path $consoleSource 'index.html') -PathType Leaf)) {
    throw "Frontend production build is missing: $consoleSource"
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
Assert-PathUnderOutputRoot $releasePath
Assert-PathUnderOutputRoot $archivePath
Assert-PathUnderOutputRoot $verificationPath

if ((Test-Path -LiteralPath $releasePath) -or
    (Test-Path -LiteralPath $archivePath)) {
    throw "Release output already exists and will not be overwritten: $ReleaseName"
}

New-Item -ItemType Directory -Path `
    (Join-Path $releasePath 'server'),
    (Join-Path $releasePath 'console') | Out-Null
Copy-Item -LiteralPath $jarSource `
    -Destination (Join-Path $releasePath 'server\nexus-server.jar')
Copy-Item -LiteralPath $consoleSource `
    -Destination (Join-Path $releasePath 'console\dist') -Recurse

$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Could not read the Git commit for the release manifest.'
}
$gitStatus = @(& git -C $repoRoot status --porcelain=v1)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not read the Git working-tree state.'
}

$artifacts = Get-ArtifactRecords $releasePath
$manifest = [ordered]@{
    version = $ReleaseName
    createdAt = [DateTimeOffset]::Now.ToString('o')
    platform = 'linux-x86_64'
    javaMajorVersion = 21
    playwrightVersion = '1.63.0'
    gitCommit = $gitCommit
    gitWorkingTreeDirty = ($gitStatus.Count -gt 0)
    artifacts = $artifacts
}
Write-Utf8Text (Join-Path $releasePath 'release-info.json') `
    (($manifest | ConvertTo-Json -Depth 5) + "`n")

Assert-ReleaseIntegrity $releasePath

& tar.exe -czf $archivePath -C $outputRoot $ReleaseName
if ($LASTEXITCODE -ne 0) {
    throw "tar failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Path $verificationPath | Out-Null
try {
    & tar.exe -xzf $archivePath -C $verificationPath
    if ($LASTEXITCODE -ne 0) {
        throw "tar verification extraction failed with exit code $LASTEXITCODE"
    }
    $verifiedReleasePath = Join-Path $verificationPath $ReleaseName
    Assert-ReleaseIntegrity $verifiedReleasePath
} finally {
    if (Test-Path -LiteralPath $verificationPath) {
        [IO.Directory]::Delete($verificationPath, $true)
    }
}

$archive = Get-Item -LiteralPath $archivePath
$archiveHash = (Get-FileHash -LiteralPath $archivePath `
        -Algorithm SHA256).Hash

Write-Output "RELEASE=$ReleaseName"
Write-Output "DIRECTORY=$releasePath"
Write-Output "ARCHIVE=$archivePath"
Write-Output "ARCHIVE_BYTES=$($archive.Length)"
Write-Output "ARCHIVE_SHA256=$archiveHash"
Write-Output "ARTIFACT_COUNT=$($artifacts.Count)"
Write-Output 'PASS: source files, manifest, archive extraction, lengths, and SHA-256 hashes match.'
