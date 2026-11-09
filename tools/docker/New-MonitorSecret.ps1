[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$destination = Join-Path $root 'data/docker-monitor-lab/grafana-admin-password'
if (Test-Path -LiteralPath $destination) { throw '监控密码文件已存在，拒绝覆盖。' }
New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
    try { $writer.Write($password) } finally { $writer.Dispose() }
} finally { $stream.Dispose() }
Write-Output 'Grafana 本地密码文件已创建；文件继承目录权限，请勿提交或分享。'
