param(
    [switch]$ApprovedLoad,
    [switch]$SkipBuild,
    [ValidateRange(1024, 65535)]
    [int]$ApiPort = 18180,
    [ValidateRange(1024, 65535)]
    [int]$ManagementPort = 18181
)

$ErrorActionPreference = 'Stop'
$serverPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repositoryPath = [IO.Path]::GetFullPath((Join-Path $serverPath '..'))
$localConfigPath = Join-Path $serverPath 'config/application-local.yml'
$mavenRepository = Join-Path $repositoryPath '.m2repo'
$javaBinDirectory = if ($env:JAVA_HOME) {
    Join-Path ([IO.Path]::GetFullPath($env:JAVA_HOME)) 'bin'
} else {
    Split-Path -Parent ((Get-Command java.exe -All -ErrorAction Stop |
        Where-Object { $_.Version.Major -ge 21 } |
        Select-Object -First 1).Source)
}
$javaExecutable = Join-Path $javaBinDirectory 'java.exe'
$javacExecutable = Join-Path $javaBinDirectory 'javac.exe'
$nodeScript = Join-Path $PSScriptRoot 'controlled-application-state-capacity-experiment.mjs'
$guardSource = Join-Path $PSScriptRoot 'ApplicationStateCapacityExperimentDatabaseGuard.java'
$runtimeRoot = Join-Path $serverPath ('target/application-state-capacity-runtime/' +
    [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss-fff'))
$guardClasses = Join-Path $runtimeRoot 'guard-classes'
$logDirectory = Join-Path $runtimeRoot 'logs'
$jobResultDirectory = Join-Path $runtimeRoot 'job-results'
$evidenceDirectory = Join-Path $runtimeRoot 'evidence'
$stdoutPath = Join-Path $runtimeRoot 'server-stdout.log'
$stderrPath = Join-Path $runtimeRoot 'server-stderr.log'
$clientOutputPath = Join-Path $runtimeRoot 'client-output.log'
$workerPreflightPath = Join-Path $runtimeRoot 'worker-preflight.log'
$serverProcess = $null
$resourceEmail = $null
$loadCompleted = $false
$primaryFailure = $null
$cleanupFailure = $null
$savedEnvironment = @{}
$environmentNames = @(
    'SPRING_PROFILES_ACTIVE',
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
    'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE',
    'SPRING_FLYWAY_URL',
    'SPRING_FLYWAY_USER',
    'SPRING_FLYWAY_PASSWORD',
    'NEXUS_JWT_SECRET',
    'NEXUS_LOG_DIR',
    'NEXUS_SCREENSHOT_JOB_RESULT_DIR',
    'SERVER_ADDRESS',
    'SERVER_PORT',
    'SERVER_TOMCAT_MBEANREGISTRY_ENABLED',
    'MANAGEMENT_SERVER_ADDRESS',
    'MANAGEMENT_SERVER_PORT',
    'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE',
    'NEXUS_EXPERIMENT_API_ORIGIN',
    'NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN',
    'NEXUS_EXPERIMENT_OUTPUT_DIR',
    'NEXUS_EXPERIMENT_SERVER_PID',
    'NEXUS_EXPERIMENT_DB_URL',
    'NEXUS_EXPERIMENT_DB_USERNAME',
    'NEXUS_EXPERIMENT_DB_PASSWORD',
    'NEXUS_STATE_LOAD_APPROVAL'
)

if (-not $ApprovedLoad) {
    throw 'Real Application state load requires the explicit -ApprovedLoad switch.'
}
if ($ApiPort -eq $ManagementPort) {
    throw 'Business and management ports must be different.'
}
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf) -or
    -not (Test-Path -LiteralPath $javacExecutable -PathType Leaf)) {
    throw "Java 21 java/javac executables are missing from: $javaBinDirectory"
}
if ((& $javacExecutable -version 2>&1) -notmatch '^javac 21(?:\.|$)') {
    throw "The controlled experiment requires Java 21 from: $javaBinDirectory"
}

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
    $userLine = $lines | Where-Object { $_ -match ($prefix + 'username\s*:') } |
        Select-Object -First 1
    $passwordLine = $lines | Where-Object { $_ -match ($prefix + 'password\s*:') } |
        Select-Object -First 1
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
        throw 'Refusing settings other than local nexus_test/nexus_test_user.'
    }
    return $settings
}

function Assert-PortFree([int]$Port) {
    if (@(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue).Count -ne 0) {
        throw "Port $Port is already listening; refusing to reuse an existing environment."
    }
}

function Wait-ForPortFree([int]$Port) {
    $deadline = [DateTimeOffset]::Now.AddSeconds(10)
    while ([DateTimeOffset]::Now -lt $deadline) {
        if (@(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue).Count -eq 0) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "Port $Port remained occupied after process cleanup."
}

function Assert-LoopbackListener([int]$Port, [int]$OwningProcess) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop)
    if ($listeners.Count -eq 0 -or
        ($listeners | Where-Object {
            $_.OwningProcess -ne $OwningProcess -or
            $_.LocalAddress -notin @('127.0.0.1', '::ffff:127.0.0.1')
        }).Count -ne 0) {
        $observed = ($listeners | ForEach-Object {
            "$($_.LocalAddress):$($_.LocalPort)/pid=$($_.OwningProcess)"
        }) -join ', '
        throw "Port $Port is not owned exclusively by the experiment JVM on loopback: $observed"
    }
}

function Wait-ForServer([Diagnostics.Process]$Process) {
    $healthUri = "http://127.0.0.1:$ManagementPort/actuator/health"
    $deadline = [DateTimeOffset]::Now.AddSeconds(90)
    while ([DateTimeOffset]::Now -lt $deadline) {
        if ($Process.HasExited) {
            throw "Controlled server exited with code $($Process.ExitCode)."
        }
        try {
            $health = Invoke-RestMethod -Uri $healthUri -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw 'Controlled server did not become healthy within 90 seconds.'
}

function Save-Environment {
    foreach ($name in $environmentNames) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
}

function Restore-Environment {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}

function Set-ExperimentEnvironment($database) {
    $jwtBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:SPRING_DATASOURCE_URL = $database.Url
    $env:SPRING_DATASOURCE_USERNAME = $database.Username
    $env:SPRING_DATASOURCE_PASSWORD = $database.Password
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '10'
    $env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '2'
    $env:SPRING_FLYWAY_URL = $database.Url
    $env:SPRING_FLYWAY_USER = $database.Username
    $env:SPRING_FLYWAY_PASSWORD = $database.Password
    $env:NEXUS_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
    $env:NEXUS_LOG_DIR = $logDirectory
    $env:NEXUS_SCREENSHOT_JOB_RESULT_DIR = $jobResultDirectory
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = [string]$ApiPort
    $env:SERVER_TOMCAT_MBEANREGISTRY_ENABLED = 'true'
    $env:MANAGEMENT_SERVER_ADDRESS = '127.0.0.1'
    $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
    $env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,metrics,prometheus'
    $env:NEXUS_EXPERIMENT_API_ORIGIN = "http://127.0.0.1:$ApiPort"
    $env:NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN = "http://127.0.0.1:$ManagementPort"
    $env:NEXUS_EXPERIMENT_OUTPUT_DIR = $evidenceDirectory
    $env:NEXUS_EXPERIMENT_DB_URL = $database.Url
    $env:NEXUS_EXPERIMENT_DB_USERNAME = $database.Username
    $env:NEXUS_EXPERIMENT_DB_PASSWORD = $database.Password
    $env:NEXUS_STATE_LOAD_APPROVAL = 'RUN_APPROVED_STATE_LOAD'
}

function Stop-ExactServer {
    if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -InputObject $serverProcess -Force -ErrorAction Stop
        if (-not $serverProcess.WaitForExit(30000)) {
            throw "Experiment JVM $($serverProcess.Id) did not exit within 30 seconds."
        }
    }
}

function Invoke-DatabaseGuard([string]$Action, [string]$Email = '', [int]$Rounds = 0) {
    $connector = Get-ChildItem -LiteralPath (
        Join-Path $mavenRepository 'com/mysql/mysql-connector-j/9.7.0') `
        -Filter 'mysql-connector-j-*.jar' -File | Select-Object -First 1
    if ($null -eq $connector) { throw 'MySQL connector 9.7.0 is missing.' }
    if (-not (Test-Path -LiteralPath (
        Join-Path $guardClasses 'ApplicationStateCapacityExperimentDatabaseGuard.class'))) {
        New-Item -ItemType Directory -Force -Path $guardClasses | Out-Null
        & $javacExecutable -Xlint:all -d $guardClasses $guardSource
        if ($LASTEXITCODE -ne 0) { throw 'Database guard compilation failed.' }
    }
    $arguments = @('-cp', "$guardClasses;$($connector.FullName)",
        'ApplicationStateCapacityExperimentDatabaseGuard', $Action)
    if (-not [string]::IsNullOrWhiteSpace($Email)) { $arguments += $Email }
    if ($Rounds -gt 0) { $arguments += [string]$Rounds }
    & $javaExecutable @arguments
    if ($LASTEXITCODE -ne 0) { throw "Database guard '$Action' failed." }
}

Save-Environment
New-Item -ItemType Directory -Force -Path $runtimeRoot, $logDirectory,
    $jobResultDirectory, $evidenceDirectory | Out-Null
$database = Get-TestDatabaseSettings

try {
    Assert-PortFree $ApiPort
    Assert-PortFree $ManagementPort
    Set-ExperimentEnvironment $database

    Push-Location $serverPath
    try {
        & node $nodeScript --worker-preflight 2>&1 |
            Tee-Object -FilePath $workerPreflightPath
        if ($LASTEXITCODE -ne 0) { throw 'Worker clock preflight failed.' }
    } finally {
        Pop-Location
    }
    Invoke-DatabaseGuard 'preflight'

    if (-not $SkipBuild) {
        Push-Location $serverPath
        try {
            & .\mvnw.cmd "-Dmaven.repo.local=$mavenRepository" -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw 'Experiment artifact build failed.' }
        } finally {
            Pop-Location
        }
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $serverPath 'target') `
        -Filter 'nexus-server-*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $jar) { throw 'Built experiment JAR is missing.' }
    $manifest = [ordered]@{
        createdAt = [DateTimeOffset]::Now.ToString('O')
        gitHead = (& git -C $repositoryPath rev-parse HEAD).Trim()
        gitStatus = @(& git -C $repositoryPath status --porcelain)
        jar = $jar.FullName
        jarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash
        apiOrigin = "http://127.0.0.1:$ApiPort"
        managementOrigin = "http://127.0.0.1:$ManagementPort"
        jvm = @{ xms = '128m'; xmx = '512m' }
        hikari = @{ maximumPoolSize = 10; minimumIdle = 2 }
        workload = @{
            applications = 1001; control = 999; rateRps = 150
            senderWorkers = 10; maxInFlightPerWorker = 8; rounds = 3
        }
    }
    $manifest | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runtimeRoot 'run-manifest.json') -Encoding utf8

    $serverProcess = Start-Process -FilePath $javaExecutable `
        -ArgumentList @('-Xms128m', '-Xmx512m', '-jar', $jar.FullName) `
        -WorkingDirectory $serverPath -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $env:NEXUS_EXPERIMENT_SERVER_PID = [string]$serverProcess.Id
    Wait-ForServer $serverProcess
    Assert-LoopbackListener $ApiPort $serverProcess.Id
    Assert-LoopbackListener $ManagementPort $serverProcess.Id

    Push-Location $serverPath
    try {
        & node $nodeScript 2>&1 | Tee-Object -FilePath $clientOutputPath
        $nodeExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $emailLine = Get-Content -LiteralPath $clientOutputPath -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^RESOURCE_EMAIL=' } | Select-Object -Last 1
    if ($emailLine) { $resourceEmail = $emailLine.Substring('RESOURCE_EMAIL='.Length) }
    if ($nodeExitCode -ne 0) { throw "Experiment client failed with code $nodeExitCode." }
    if (-not $resourceEmail) { throw 'Experiment resource email was not captured.' }
    Invoke-DatabaseGuard 'wait-accounting' $resourceEmail 3
    $loadCompleted = $true
} catch {
    $primaryFailure = $_
    try {
        ($_ | Out-String) | Set-Content -LiteralPath (
            Join-Path $runtimeRoot 'run-failure.txt') -Encoding utf8
    } catch {
        # Preserve the original failure.
    }
} finally {
    try {
        if (-not $resourceEmail -and (Test-Path -LiteralPath $clientOutputPath)) {
            $emailLine = Get-Content -LiteralPath $clientOutputPath |
                Where-Object { $_ -match '^RESOURCE_EMAIL=' } | Select-Object -Last 1
            if ($emailLine) { $resourceEmail = $emailLine.Substring('RESOURCE_EMAIL='.Length) }
        }
        Stop-ExactServer
        if ($resourceEmail) {
            if ($loadCompleted) { Invoke-DatabaseGuard 'inspect' $resourceEmail 3 }
            Invoke-DatabaseGuard 'cleanup' $resourceEmail
            Invoke-DatabaseGuard 'verify-clean' $resourceEmail
        }
        if ($null -ne $serverProcess) {
            Wait-ForPortFree $ApiPort
            Wait-ForPortFree $ManagementPort
        }
    } catch {
        $cleanupFailure = $_
    } finally {
        Restore-Environment
    }
}

if ($null -ne $primaryFailure) {
    if ($null -ne $cleanupFailure) {
        Write-Warning "Cleanup also failed: $($cleanupFailure.Exception.Message)"
    }
    throw $primaryFailure
}
if ($null -ne $cleanupFailure) { throw $cleanupFailure }

Write-Output 'APPLICATION_STATE_CAPACITY_EXPERIMENT_OK'
Write-Output "EVIDENCE_DIRECTORY=$runtimeRoot"
