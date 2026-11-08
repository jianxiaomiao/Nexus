[CmdletBinding()]
param(
    [string]$Destination
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $Destination) {
    $Destination = Join-Path $repositoryRoot 'data/docker-compose-lab/local.env'
}
$destinationPath = [IO.Path]::GetFullPath($Destination)
$seccompPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'playwright-1.63.0-seccomp.json')).Replace('\', '/')
if ($seccompPath -match "['\r\n]") {
    throw 'Seccomp 路径包含无法写入配置的字符。'
}
if (-not (Test-Path -LiteralPath $seccompPath -PathType Leaf)) {
    throw '找不到项目中的 seccomp 配置。'
}

$names = @('NEXUS_LAB_DB_PASSWORD', 'NEXUS_LAB_DB_ROOT_PASSWORD', 'NEXUS_LAB_JWT_SECRET', 'NEXUS_LAB_WEBHOOK_KEY', 'NEXUS_LAB_SECCOMP_PATH')
$conflicts = @($names | Where-Object { Test-Path -LiteralPath "Env:$_" })
if ($conflicts.Count -gt 0) {
    throw "当前进程中的同名环境变量会覆盖文件值，请先处理：$($conflicts -join ', ')"
}
if (Test-Path -LiteralPath $destinationPath) {
    throw '目标文件已存在，拒绝覆盖；已有数据库及加密数据必须保留原密钥。'
}

$directory = [IO.Path]::GetDirectoryName($destinationPath)
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$lines = @(
    "NEXUS_LAB_DB_PASSWORD=$([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))"
    "NEXUS_LAB_DB_ROOT_PASSWORD=$([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))"
    "NEXUS_LAB_JWT_SECRET=$([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))"
    "NEXUS_LAB_WEBHOOK_KEY=$([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))"
    "NEXUS_LAB_SECCOMP_PATH='$seccompPath'"
)
$stream = [IO.File]::Open($destinationPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
    try {
        foreach ($line in $lines) { $writer.WriteLine($line) }
    } finally {
        $writer.Dispose()
    }
} finally {
    $stream.Dispose()
}
Write-Output "已创建本地秘密文件：$destinationPath"
Write-Output '文件包含明文秘密；请检查目录访问权限，不要提交、打印或分享。'
