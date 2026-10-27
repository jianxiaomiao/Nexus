[CmdletBinding()]
param(
    [string]$ReleaseName = 'V2026.10.27.1__v9-obs1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$releaseOutput = Join-Path $repoRoot 'nexus-server\target\linux-release'
$releaseDirectory = Join-Path $releaseOutput $ReleaseName
$releaseArchive = Join-Path $releaseOutput "$ReleaseName.tar.gz"
$bundleName = 'nexus-v9-obs1-deploy'
$bundleDirectory = Join-Path $releaseOutput $bundleName
$bundleArchive = Join-Path $releaseOutput "$bundleName.tar.gz"
$uploadChecksums = Join-Path $releaseOutput 'UPLOAD_SHA256SUMS.txt'
$scriptSource = Join-Path $PSScriptRoot 'v9-obs1'

function Write-Utf8Lf([string]$Path, [string]$Value) {
    $normalized = $Value.Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($Path, $normalized, [Text.UTF8Encoding]::new($false))
}

function Copy-TextAsLf([string]$Source, [string]$Destination) {
    Write-Utf8Lf $Destination ([IO.File]::ReadAllText($Source))
}

if (-not (Test-Path -LiteralPath $releaseArchive -PathType Leaf)) {
    throw "Release archive is missing: $releaseArchive"
}
if (-not (Test-Path -LiteralPath $releaseDirectory -PathType Container)) {
    throw "Release directory is missing: $releaseDirectory"
}
if ((Test-Path -LiteralPath $bundleDirectory) -or
    (Test-Path -LiteralPath $bundleArchive) -or
    (Test-Path -LiteralPath $uploadChecksums)) {
    throw 'Deployment bundle outputs already exist and will not be overwritten.'
}

$gitStatus = @(& git -C $repoRoot status --porcelain=v1)
if ($LASTEXITCODE -ne 0 -or $gitStatus.Count -ne 0) {
    throw 'The repository must be clean before creating the deployment bundle.'
}
$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Could not read the Git commit.'
}

$releaseManifest = Get-Content -LiteralPath `
    (Join-Path $releaseDirectory 'release-info.json') -Raw | ConvertFrom-Json
if ($releaseManifest.version -ne $ReleaseName -or
    $releaseManifest.gitCommit -ne $gitCommit -or
    $releaseManifest.gitWorkingTreeDirty -ne $false) {
    throw 'Release manifest does not identify the current clean commit.'
}

New-Item -ItemType Directory -Path $bundleDirectory | Out-Null

$files = @(
    '01-stage-release.sh',
    '02-deploy-release.sh',
    '03-accept-v9-obs1.py',
    '04-promote-release.sh',
    '05-rollback-release.sh',
    'verify_release.py',
    'verify_v8_backup.py',
    'preflight.py',
    'check_runtime.py'
)
foreach ($name in $files) {
    Copy-TextAsLf (Join-Path $scriptSource $name) (Join-Path $bundleDirectory $name)
}
Copy-TextAsLf (Join-Path $repoRoot 'nexus-server\config\application-prod.yml.example') `
    (Join-Path $bundleDirectory 'application-prod.yml')
Copy-TextAsLf (Join-Path $PSScriptRoot 'nginx-tls-loopback.conf') `
    (Join-Path $bundleDirectory 'nginx-tls-loopback.conf')

$releaseHash = (Get-FileHash -LiteralPath $releaseArchive -Algorithm SHA256).Hash
Write-Utf8Lf (Join-Path $bundleDirectory 'release.sha256') `
    "$releaseHash  $ReleaseName.tar.gz`n"

$readme = @"
Nexus V9 + OBS-1 Ubuntu loopback deployment

Upload these three files to /var/tmp:
  $ReleaseName.tar.gz
  $bundleName.tar.gz
  UPLOAD_SHA256SUMS.txt

On the ECS Alibaba Cloud remote terminal:

  cd /var/tmp
  sha256sum -c UPLOAD_SHA256SUMS.txt
  sudo install -d -o root -g root -m 0700 /opt/nexus/deploy-tools
  sudo tar -xzf $bundleName.tar.gz -C /opt/nexus/deploy-tools --no-same-owner
  sudo chown -R root:root /opt/nexus/deploy-tools/$bundleName
  sudo chmod -R go-rwx /opt/nexus/deploy-tools/$bundleName

Stage only (does not restart services or switch current):
  sudo bash /opt/nexus/deploy-tools/$bundleName/01-stage-release.sh /var/tmp/$ReleaseName.tar.gz /opt/nexus/deploy-tools/$bundleName/release.sha256

Cut over to the candidate and run deterministic infrastructure checks:
  sudo bash /opt/nexus/deploy-tools/$bundleName/02-deploy-release.sh /opt/nexus/deploy-tools/$bundleName/application-prod.yml /opt/nexus/deploy-tools/$bundleName/nginx-tls-loopback.conf

Run one real UUID + asynchronous screenshot acceptance and exact cleanup:
  sudo python3 /opt/nexus/deploy-tools/$bundleName/03-accept-v9-obs1.py

Promote only after acceptance passes:
  sudo bash /opt/nexus/deploy-tools/$bundleName/04-promote-release.sh

Before promotion, restore the full known V8 state if deployment or acceptance fails:
  sudo bash /opt/nexus/deploy-tools/$bundleName/05-rollback-release.sh --restore-v8

Do not run promote and rollback together. The rollback command restores the pre-cutover
database dump and is intentionally refused after promotion.
"@
Write-Utf8Lf (Join-Path $bundleDirectory 'README.txt') ($readme + "`n")

$bundleArtifacts = @(
    Get-ChildItem -LiteralPath $bundleDirectory -File |
        ForEach-Object {
            [ordered]@{
                path = $_.Name
                length = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        } |
        Sort-Object path
)
$bundleManifest = [ordered]@{
    release = $ReleaseName
    releaseSha256 = $releaseHash
    gitCommit = $gitCommit
    createdAt = [DateTimeOffset]::Now.ToString('o')
    files = $bundleArtifacts
}
Write-Utf8Lf (Join-Path $bundleDirectory 'deployment-info.json') `
    (($bundleManifest | ConvertTo-Json -Depth 4) + "`n")

& tar.exe -czf $bundleArchive -C $releaseOutput $bundleName
if ($LASTEXITCODE -ne 0) {
    throw "tar failed with exit code $LASTEXITCODE"
}
$bundleHash = (Get-FileHash -LiteralPath $bundleArchive -Algorithm SHA256).Hash
Write-Utf8Lf $uploadChecksums (
    "$releaseHash  $ReleaseName.tar.gz`n" +
    "$bundleHash  $bundleName.tar.gz`n")

Write-Output "RELEASE_ARCHIVE=$releaseArchive"
Write-Output "RELEASE_SHA256=$releaseHash"
Write-Output "DEPLOY_BUNDLE=$bundleArchive"
Write-Output "DEPLOY_BUNDLE_SHA256=$bundleHash"
Write-Output "UPLOAD_CHECKSUMS=$uploadChecksums"
Write-Output 'PASS: clean release and deployment bundle are ready for upload.'
