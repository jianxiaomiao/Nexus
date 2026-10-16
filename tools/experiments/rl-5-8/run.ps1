param(
    [string]$JavaExe = 'D:\java21\bin\java.exe'
)

$ErrorActionPreference = 'Stop'

if (!(Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
    throw 'Java executable not found; supply -JavaExe with the actual JDK executable.'
}

& $JavaExe (Join-Path $PSScriptRoot 'BoundedApplicationQueueExperiment.java')
if ($LASTEXITCODE -ne 0) {
    throw 'RL-5..RL-8 experiment failed.'
}
