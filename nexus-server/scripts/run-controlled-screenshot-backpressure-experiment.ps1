param(
    [ValidateSet('Preflight', 'Calibrate', 'Load')]
    [string]$Mode = 'Preflight',
    [double]$RateRps = 0,
    [ValidateRange(10, 60)]
    [int]$DurationSeconds = 30,
    [ValidateSet('Forbidden', 'Observe', 'Required')]
    [string]$BackpressureExpectation = 'Observe',
    [switch]$ApprovedLoad,
    [switch]$ApprovedCalibration,
    [switch]$SkipBuild,
    [ValidateRange(1024, 65535)]
    [int]$ApiPort = 18180,
    [ValidateRange(1024, 65535)]
    [int]$ManagementPort = 18181,
    [string]$PlaywrightBrowsersPath = $env:PLAYWRIGHT_BROWSERS_PATH
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
        Where-Object { $_.Version.Major -ge 21 -and $_.Source -notmatch '\\Oracle\\Java\\' } |
        Select-Object -First 1).Source)
}
$javacExecutable = Join-Path $javaBinDirectory 'javac.exe'
$javaExecutable = Join-Path $javaBinDirectory 'java.exe'
$nodeScript = Join-Path $PSScriptRoot 'controlled-screenshot-backpressure-experiment.mjs'
$guardSource = Join-Path $PSScriptRoot 'ScreenshotBackpressureExperimentDatabaseGuard.java'
$runtimeRoot = Join-Path $serverPath ('target/screenshot-backpressure-runtime/' +
    [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss-fff'))
$guardClasses = Join-Path $runtimeRoot 'guard-classes'
$logDirectory = Join-Path $runtimeRoot 'logs'
$jobResultDirectory = Join-Path $runtimeRoot 'job-results'
$evidenceDirectory = Join-Path $runtimeRoot 'evidence'
$stdoutPath = Join-Path $runtimeRoot 'server-stdout.log'
$stderrPath = Join-Path $runtimeRoot 'server-stderr.log'
$clientOutputPath = Join-Path $runtimeRoot 'client-output.log'
$serverProcess = $null
$serverStartedAt = $null
$resourceEmail = $null
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
    'NEXUS_EXPERIMENT_SCREENSHOT_FIXTURE_DELAY_MS',
    'PLAYWRIGHT_BROWSERS_PATH',
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
    'NEXUS_EXPERIMENT_BROWSER_ROOT',
    'NEXUS_EXPERIMENT_DB_URL',
    'NEXUS_EXPERIMENT_DB_USERNAME',
    'NEXUS_EXPERIMENT_DB_PASSWORD',
    'NEXUS_SCREENSHOT_EXPERIMENT_RATE_RPS',
    'NEXUS_SCREENSHOT_EXPERIMENT_DURATION_SECONDS',
    'NEXUS_SCREENSHOT_BACKPRESSURE_EXPECTATION',
    'NEXUS_SCREENSHOT_LOAD_APPROVAL'
)

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
        throw 'Refusing settings other than local nexus_test/nexus_test_user.'
    }
    return $settings
}

function Assert-PortFree([int]$Port) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($listeners.Count -ne 0) {
        throw "Port $Port is already listening; refusing to reuse an existing environment."
    }
}

function Wait-ForPortFree([int]$Port) {
    $deadline = [DateTimeOffset]::Now.AddSeconds(10)
    while ([DateTimeOffset]::Now -lt $deadline) {
        if (@(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue).Count -eq 0) {
            return
        }
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
            if ($health.status -eq 'UP') {
                $stdout = if (Test-Path -LiteralPath $stdoutPath) {
                    Get-Content -Raw -LiteralPath $stdoutPath
                } else { '' }
                if ($stdout -notmatch 'CONTROLLED_SCREENSHOT_EXPERIMENT_FETCHER_ACTIVE') {
                    throw 'Server is UP but the controlled offline Fetcher marker is absent.'
                }
                return
            }
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
        [Environment]::SetEnvironmentVariable(
            $name, $savedEnvironment[$name], 'Process')
    }
}

function Set-ExperimentEnvironment($database, [string]$browserPath) {
    $jwtBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:SPRING_DATASOURCE_URL = $database.Url
    $env:SPRING_DATASOURCE_USERNAME = $database.Username
    $env:SPRING_DATASOURCE_PASSWORD = $database.Password
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '4'
    $env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '1'
    $env:SPRING_FLYWAY_URL = $database.Url
    $env:SPRING_FLYWAY_USER = $database.Username
    $env:SPRING_FLYWAY_PASSWORD = $database.Password
    $env:NEXUS_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
    $env:NEXUS_LOG_DIR = $logDirectory
    $env:NEXUS_SCREENSHOT_JOB_RESULT_DIR = $jobResultDirectory
    $env:NEXUS_EXPERIMENT_SCREENSHOT_FIXTURE_DELAY_MS = '2000'
    $env:PLAYWRIGHT_BROWSERS_PATH = $browserPath
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = [string]$ApiPort
    $env:SERVER_TOMCAT_MBEANREGISTRY_ENABLED = 'true'
    $env:MANAGEMENT_SERVER_ADDRESS = '127.0.0.1'
    $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
    $env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,metrics,prometheus'
    $env:NEXUS_EXPERIMENT_API_ORIGIN = "http://127.0.0.1:$ApiPort"
    $env:NEXUS_EXPERIMENT_MANAGEMENT_ORIGIN = "http://127.0.0.1:$ManagementPort"
    $env:NEXUS_EXPERIMENT_OUTPUT_DIR = $evidenceDirectory
    $env:NEXUS_EXPERIMENT_BROWSER_ROOT = $browserPath
    $env:NEXUS_EXPERIMENT_DB_URL = $database.Url
    $env:NEXUS_EXPERIMENT_DB_USERNAME = $database.Username
    $env:NEXUS_EXPERIMENT_DB_PASSWORD = $database.Password
}

function Stop-ExactExperimentProcesses {
    if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -InputObject $serverProcess -Force -ErrorAction Stop
        if (-not $serverProcess.WaitForExit(30000)) {
            throw "Experiment JVM $($serverProcess.Id) did not exit within 30 seconds."
        }
        if (Get-Process -Id $serverProcess.Id -ErrorAction SilentlyContinue) {
            throw "Experiment JVM $($serverProcess.Id) still exists after termination."
        }
    }
    if ($null -ne $serverStartedAt) {
        $browserRoot = [IO.Path]::GetFullPath($PlaywrightBrowsersPath)
        $ownedBrowsers = @(Get-Process -Name 'chrome-headless-shell' -ErrorAction SilentlyContinue |
            Where-Object {
                try {
                    $_.StartTime -ge $serverStartedAt.AddSeconds(-2) -and
                    $_.Path -and
                    [IO.Path]::GetFullPath($_.Path).StartsWith(
                        $browserRoot, [StringComparison]::OrdinalIgnoreCase)
                } catch { $false }
            })
        foreach ($browser in $ownedBrowsers) {
            Stop-Process -Id $browser.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

function Remove-JobResultsSafely {
    if (-not (Test-Path -LiteralPath $jobResultDirectory)) { return }
    $resolvedRoot = [IO.Path]::GetFullPath($runtimeRoot).TrimEnd('\') + '\'
    $resolvedTarget = [IO.Path]::GetFullPath($jobResultDirectory)
    if (-not $resolvedTarget.StartsWith(
        $resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove a result directory outside the experiment runtime root.'
    }
    Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
}

function Invoke-DatabaseGuard([string]$Action, [string]$Email = '') {
    $connector = Get-ChildItem -LiteralPath (
        Join-Path $mavenRepository 'com/mysql/mysql-connector-j/9.7.0') `
        -Filter 'mysql-connector-j-*.jar' -File | Select-Object -First 1
    if ($null -eq $connector) { throw 'MySQL connector 9.7.0 is missing.' }
    if (-not (Test-Path -LiteralPath (
        Join-Path $guardClasses 'ScreenshotBackpressureExperimentDatabaseGuard.class'))) {
        New-Item -ItemType Directory -Force -Path $guardClasses | Out-Null
        & $javacExecutable -Xlint:all -d $guardClasses $guardSource
        if ($LASTEXITCODE -ne 0) { throw 'Database guard compilation failed.' }
    }
    $arguments = @('-cp', "$guardClasses;$($connector.FullName)",
        'ScreenshotBackpressureExperimentDatabaseGuard', $Action)
    if (-not [string]::IsNullOrWhiteSpace($Email)) { $arguments += $Email }
    & $javaExecutable @arguments
    if ($LASTEXITCODE -ne 0) { throw "Database guard '$Action' failed." }
}

if ($ApiPort -eq $ManagementPort) {
    throw 'Business and management ports must be different.'
}
if ($Mode -eq 'Load') {
    if (-not $ApprovedLoad) {
        throw 'Load mode requires the explicit -ApprovedLoad switch.'
    }
    if ($RateRps -le 0 -or $RateRps -gt 5) {
        throw 'Load rate must be in (0, 5] RPS.'
    }
}
if ($Mode -eq 'Calibrate' -and -not $ApprovedCalibration) {
    throw 'Calibration starts one cold diagnostic and three warm sequential Chromium jobs and requires -ApprovedCalibration.'
}
if ([string]::IsNullOrWhiteSpace($PlaywrightBrowsersPath)) {
    throw 'PlaywrightBrowsersPath is required.'
}
$PlaywrightBrowsersPath = [IO.Path]::GetFullPath($PlaywrightBrowsersPath)
if (-not (Test-Path -LiteralPath $PlaywrightBrowsersPath -PathType Container)) {
    throw "Playwright browser root does not exist: $PlaywrightBrowsersPath"
}

Save-Environment
New-Item -ItemType Directory -Force -Path $runtimeRoot, $logDirectory,
    $evidenceDirectory | Out-Null
$database = Get-TestDatabaseSettings

try {
    Assert-PortFree $ApiPort
    Assert-PortFree $ManagementPort
    Set-ExperimentEnvironment $database $PlaywrightBrowsersPath
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
    $launcherClass = Join-Path $serverPath (
        'target/test-classes/com/nexus/screenshot/service/' +
        'ControlledScreenshotExperimentApplication.class')
    if ($null -eq $jar -or -not (Test-Path -LiteralPath $launcherClass -PathType Leaf)) {
        throw 'Built JAR or controlled test launcher is missing.'
    }

    $manifest = [ordered]@{
        mode = $Mode
        createdAt = [DateTimeOffset]::Now.ToString('O')
        gitHead = (& git -C $repositoryPath rev-parse HEAD).Trim()
        gitStatus = @(& git -C $repositoryPath status --porcelain)
        jar = $jar.FullName
        jarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash
        launcherSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $launcherClass).Hash
        browserRoot = $PlaywrightBrowsersPath
        apiOrigin = "http://127.0.0.1:$ApiPort"
        managementOrigin = "http://127.0.0.1:$ManagementPort"
        hikari = @{ maximumPoolSize = 4; minimumIdle = 1 }
        fixtureDelayMs = 2000
        rateRps = if ($Mode -eq 'Load') { $RateRps } else { $null }
        durationSeconds = if ($Mode -eq 'Load') { $DurationSeconds } else { $null }
        backpressureExpectation = if ($Mode -eq 'Load') {
            $BackpressureExpectation.ToLowerInvariant()
        } else { $null }
    }
    $manifest | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runtimeRoot 'run-manifest.json') -Encoding utf8

    $javaArguments = @(
        "-Dloader.path=$(Join-Path $serverPath 'target/test-classes')",
        '-Dloader.main=com.nexus.screenshot.service.ControlledScreenshotExperimentApplication',
        '-cp', $jar.FullName,
        'org.springframework.boot.loader.launch.PropertiesLauncher'
    )
    $serverStartedAt = Get-Date
    $serverProcess = Start-Process -FilePath $javaExecutable -ArgumentList $javaArguments `
        -WorkingDirectory $serverPath -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $env:NEXUS_EXPERIMENT_SERVER_PID = [string]$serverProcess.Id
    Wait-ForServer $serverProcess
    Assert-LoopbackListener $ApiPort $serverProcess.Id
    Assert-LoopbackListener $ManagementPort $serverProcess.Id

    $nodeMode = switch ($Mode) {
        'Preflight' { '--preflight' }
        'Calibrate' { '--calibrate' }
        'Load' { '--load' }
    }
    if ($Mode -eq 'Load') {
        $env:NEXUS_SCREENSHOT_EXPERIMENT_RATE_RPS = [string]$RateRps
        $env:NEXUS_SCREENSHOT_EXPERIMENT_DURATION_SECONDS = [string]$DurationSeconds
        $env:NEXUS_SCREENSHOT_BACKPRESSURE_EXPECTATION =
            $BackpressureExpectation.ToLowerInvariant()
        $env:NEXUS_SCREENSHOT_LOAD_APPROVAL = 'RUN_APPROVED_SCREENSHOT_LOAD'
    }
    Push-Location $serverPath
    try {
        & node $nodeScript $nodeMode 2>&1 | Tee-Object -FilePath $clientOutputPath
        $nodeExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $emailLine = Get-Content -LiteralPath $clientOutputPath -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^RESOURCE_EMAIL=' } | Select-Object -Last 1
    if ($emailLine) { $resourceEmail = $emailLine.Substring('RESOURCE_EMAIL='.Length) }
    if ($nodeExitCode -ne 0) { throw "Experiment client failed with code $nodeExitCode." }
} catch {
    $primaryFailure = $_
    try {
        ($_ | Out-String) | Set-Content -LiteralPath (
            Join-Path $runtimeRoot 'run-failure.txt') -Encoding utf8
    } catch {
        # Preserve the original experiment failure even if evidence writing fails.
    }
} finally {
    try {
        Stop-ExactExperimentProcesses
        if (-not $resourceEmail) {
            $resourceManifest = Get-ChildItem -LiteralPath $evidenceDirectory `
                -Filter 'resource-manifest.json' -File -Recurse -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($resourceManifest) {
                $resourceEmail = (Get-Content -Raw -LiteralPath $resourceManifest.FullName |
                    ConvertFrom-Json).email
            }
        }
        if ($resourceEmail) {
            Invoke-DatabaseGuard 'cleanup' $resourceEmail
            Invoke-DatabaseGuard 'verify-clean' $resourceEmail
        }
        Remove-JobResultsSafely
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
if ($null -ne $cleanupFailure) {
    throw $cleanupFailure
}

Write-Output "SCREENSHOT_EXPERIMENT_$($Mode.ToUpperInvariant())_OK"
Write-Output "EVIDENCE_DIRECTORY=$runtimeRoot"
