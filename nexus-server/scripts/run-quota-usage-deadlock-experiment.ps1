param(
    [switch]$PreflightOnly,
    [switch]$UseWorkloadAsObserver,
    [string]$ObserverConfigPath = ''
)

$ErrorActionPreference = 'Stop'
$serverPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repositoryPath = [IO.Path]::GetFullPath((Join-Path $serverPath '..'))
$localConfigPath = Join-Path $serverPath 'config/application-local.yml'
$observerPath = if ([string]::IsNullOrWhiteSpace($ObserverConfigPath)) {
    Join-Path $serverPath 'config/deadlock-observer.local.properties'
} else {
    [IO.Path]::GetFullPath($ObserverConfigPath)
}
$classesPath = Join-Path $serverPath 'target/quota-usage-deadlock-experiment/classes'
$javaSource = Join-Path $PSScriptRoot 'QuotaUsageDeadlockExperiment.java'

function Get-YamlScalar([string]$Line, [string]$Name, [bool]$Commented) {
    $prefix = if ($Commented) { '^\s*#\s*' } else { '^\s*' }
    $value = $Line -replace ($prefix + [regex]::Escape($Name) + '\s*:\s*'), ''
    return $value.Trim().Trim('"').Trim("'")
}

function Get-TestDatabaseSettings {
    if (-not (Test-Path -LiteralPath $localConfigPath -PathType Leaf)) {
        throw "Missing local config: $localConfigPath"
    }
    $lines = Get-Content -LiteralPath $localConfigPath
    $activeUrl = $lines | Where-Object {
        $_ -match '^\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
    } | Select-Object -First 1
    $commented = $null -eq $activeUrl
    $urlLine = if ($commented) {
        $lines | Where-Object {
            $_ -match '^\s*#\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
        } | Select-Object -First 1
    } else { $activeUrl }
    $prefix = if ($commented) { '^\s*#\s*' } else { '^\s*' }
    $userLine = $lines | Where-Object {
        $_ -match ($prefix + 'username\s*:')
    } | Select-Object -First 1
    $passwordLine = $lines | Where-Object {
        $_ -match ($prefix + 'password\s*:')
    } | Select-Object -First 1
    if (-not $urlLine -or -not $userLine -or -not $passwordLine) {
        throw 'Dedicated nexus_test URL, username, or password is missing.'
    }
    $settings = [pscustomobject]@{
        Url = Get-YamlScalar $urlLine 'url' $commented
        Username = Get-YamlScalar $userLine 'username' $commented
        Password = Get-YamlScalar $passwordLine 'password' $commented
    }
    if ($settings.Url -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/nexus_test(?:\?|$)' -or
        $settings.Username -ne 'nexus_test_user' -or
        [string]::IsNullOrWhiteSpace($settings.Password)) {
        throw 'Refusing workload settings other than local nexus_test/nexus_test_user.'
    }
    return $settings
}

function Get-ObserverSettings {
    if (-not (Test-Path -LiteralPath $observerPath -PathType Leaf)) {
        throw "Missing ignored observer config: $observerPath"
    }
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $observerPath) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) { throw "Invalid observer property line in $observerPath" }
        $properties[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }
    foreach ($required in 'url', 'username', 'password') {
        if ([string]::IsNullOrWhiteSpace($properties[$required])) {
            throw "Observer property '$required' is required."
        }
    }
    if ($properties.url -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/(?:\?|nexus_test(?:\?|$))') {
        throw 'Observer URL must target only the local MySQL instance.'
    }
    return [pscustomobject]@{
        Url = $properties.url
        Username = $properties.username
        Password = $properties.password
    }
}

$userProfilePath = [Environment]::GetFolderPath('UserProfile')
$connector = Join-Path $userProfilePath '.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'
if (-not (Test-Path -LiteralPath $connector -PathType Leaf)) {
    throw "Missing MySQL connector: $connector"
}

$workload = Get-TestDatabaseSettings
$observer = if ($UseWorkloadAsObserver) { $workload } else { Get-ObserverSettings }
New-Item -ItemType Directory -Force -Path $classesPath | Out-Null
& javac -Xlint:all -d $classesPath $javaSource
if ($LASTEXITCODE -ne 0) { throw 'Deadlock experiment compilation failed.' }

$oldLocation = Get-Location
try {
    $env:NEXUS_EXPERIMENT_DB_URL = $workload.Url
    $env:NEXUS_EXPERIMENT_DB_USERNAME = $workload.Username
    $env:NEXUS_EXPERIMENT_DB_PASSWORD = $workload.Password
    $env:NEXUS_EXPERIMENT_OBSERVER_DB_URL = $observer.Url
    $env:NEXUS_EXPERIMENT_OBSERVER_DB_USERNAME = $observer.Username
    $env:NEXUS_EXPERIMENT_OBSERVER_DB_PASSWORD = $observer.Password
    Set-Location $serverPath
    $action = if ($PreflightOnly) { 'preflight' } else { 'run' }
    & java -cp "$classesPath;$connector" QuotaUsageDeadlockExperiment $action
    if ($LASTEXITCODE -ne 0) { throw "Deadlock experiment '$action' failed." }
} finally {
    Set-Location $oldLocation
    Remove-Item Env:\NEXUS_EXPERIMENT_DB_URL,
        Env:\NEXUS_EXPERIMENT_DB_USERNAME,
        Env:\NEXUS_EXPERIMENT_DB_PASSWORD,
        Env:\NEXUS_EXPERIMENT_OBSERVER_DB_URL,
        Env:\NEXUS_EXPERIMENT_OBSERVER_DB_USERNAME,
        Env:\NEXUS_EXPERIMENT_OBSERVER_DB_PASSWORD -ErrorAction SilentlyContinue
}
