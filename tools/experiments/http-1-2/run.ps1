param([string]$JavaExe = 'D:\java21\bin\java.exe')
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $JavaExe -PathType Leaf)) { throw 'Java executable not found.' }
& $JavaExe (Join-Path $PSScriptRoot 'AuthQuotaOrderExperiment.java')
if ($LASTEXITCODE -ne 0) { throw 'HTTP-1/2 experiment failed.' }
