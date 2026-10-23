param(
    [string]$DeployRoot = 'D:\NexusDeploy',
    [string]$ReleaseName = 'V1.0.0__initial-deployment',
    [ValidateRange(1024, 65535)]
    [int]$BackendPort = 18081,
    [string]$JavaExe = '',
    [switch]$VerifyScreenshotBrowserPath,
    [switch]$InjectMissingConfigFailure,
    [string]$ScreenshotArticleUrl = 'https://blog.csdn.net/qq_46429858/article/details/115696591'
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$serverRoot = Join-Path $repoRoot 'nexus-server'
$localConfigPath = Join-Path $serverRoot 'config\application-local.yml'
$sourceJarPath = Join-Path $serverRoot 'target\nexus-server-0.0.1-SNAPSHOT.jar'
$sourceDistPath = Join-Path $repoRoot 'nexus-console\dist'
$sourceBrowserRoot = Join-Path $env:LOCALAPPDATA 'ms-playwright'
$deployRootPath = [IO.Path]::GetFullPath($DeployRoot)
$releaseRoot = Join-Path $deployRootPath 'releases'
$releasePath = Join-Path $releaseRoot $ReleaseName
$currentPath = Join-Path $deployRootPath 'current'
$stateRoot = Join-Path $deployRootPath 'state'
$lastSuccessfulPath = Join-Path $stateRoot 'last-successful.txt'
$rollbackReleasePath = Join-Path $stateRoot 'rollback-release.txt'
$activeConfigPath = Join-Path $deployRootPath 'config\application-prod.yml'
$configSnapshotPath = Join-Path $deployRootPath `
    "config\history\$ReleaseName\application-prod.yml"
$browserRoot = Join-Path $deployRootPath 'browsers\playwright-1.63.0'
$screenshotDiagnosticPath = Join-Path $serverRoot 'scripts\diagnose-screenshot.ps1'
$runId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $PID
$acceptanceRoot = Join-Path $deployRootPath "logs\deploy\acceptance-$runId"
$standardOutputPath = Join-Path $acceptanceRoot 'backend-output.log'
$standardErrorPath = Join-Path $acceptanceRoot 'backend-error.log'
$applicationLogRoot = Join-Path $deployRootPath "logs\java\acceptance-$runId"

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
        throw "Path escapes deployment root: $fullPath"
    }
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Get-RelativeFileMap([string]$Root) {
    $map = @{}
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse) {
        $relative = $file.FullName.Substring($Root.Length + 1).Replace('\', '/')
        $map[$relative] = $file
    }
    return $map
}

function Assert-ReleaseManifest {
    $manifestPath = Join-Path $releasePath 'release-info.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Missing release manifest: $manifestPath"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.version -ne $ReleaseName) {
        throw "Release manifest version mismatch: $($manifest.version)"
    }
    if ($manifest.playwrightVersion -ne '1.63.0') {
        throw "Unexpected Playwright version in release manifest: $($manifest.playwrightVersion)"
    }

    $actualFiles = Get-RelativeFileMap $releasePath
    $actualFiles.Remove('release-info.json') | Out-Null
    $expectedArtifacts = @($manifest.artifacts)
    if ($actualFiles.Count -ne $expectedArtifacts.Count) {
        throw "Release file count differs from manifest: actual=$($actualFiles.Count) expected=$($expectedArtifacts.Count)"
    }
    foreach ($artifact in $expectedArtifacts) {
        $relative = [string]$artifact.path
        if (-not $actualFiles.ContainsKey($relative)) {
            throw "Manifest artifact is missing from release: $relative"
        }
        $file = $actualFiles[$relative]
        if ($file.Length -ne [long]$artifact.length) {
            throw "Artifact length changed: $relative"
        }
        if ((Get-FileSha256 $file.FullName) -ne [string]$artifact.sha256) {
            throw "Artifact SHA-256 changed: $relative"
        }
    }

    $writableFiles = @(Get-ChildItem -LiteralPath $releasePath -File -Recurse |
        Where-Object { -not $_.IsReadOnly })
    if ($writableFiles.Count -ne 0) {
        throw "Release contains writable files: $($writableFiles.Count)"
    }
    return $manifest
}

function Assert-CurrentBuildMatchesRelease {
    if (-not (Test-Path -LiteralPath $sourceJarPath -PathType Leaf)) {
        throw "Current build JAR is missing: $sourceJarPath"
    }
    $releaseJarPath = Join-Path $releasePath 'server\nexus-server.jar'
    if ((Get-FileSha256 $sourceJarPath) -ne (Get-FileSha256 $releaseJarPath)) {
        throw 'Copied release JAR does not match the current build JAR.'
    }

    $sourceFiles = Get-RelativeFileMap $sourceDistPath
    $releaseDistPath = Join-Path $releasePath 'console\dist'
    $targetFiles = Get-RelativeFileMap $releaseDistPath
    if ($sourceFiles.Count -ne $targetFiles.Count) {
        throw "Copied dist file count mismatch: source=$($sourceFiles.Count) target=$($targetFiles.Count)"
    }
    foreach ($relative in $sourceFiles.Keys) {
        if (-not $targetFiles.ContainsKey($relative)) {
            throw "Copied dist file is missing: $relative"
        }
        if ($sourceFiles[$relative].Length -ne $targetFiles[$relative].Length -or
            (Get-FileSha256 $sourceFiles[$relative].FullName) -ne
            (Get-FileSha256 $targetFiles[$relative].FullName)) {
            throw "Copied dist file differs from current build: $relative"
        }
    }
}

function Assert-BrowserCopy {
    $relativeExecutables = @(
        'chromium-1243\chrome-win64\chrome.exe',
        'chromium_headless_shell-1243\chrome-headless-shell-win64\chrome-headless-shell.exe',
        'ffmpeg-1011\ffmpeg-win64.exe',
        'winldd-1007\PrintDeps.exe'
    )
    foreach ($relative in $relativeExecutables) {
        $source = Join-Path $sourceBrowserRoot $relative
        $target = Join-Path $browserRoot $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or
            -not (Test-Path -LiteralPath $target -PathType Leaf)) {
            throw "Required Playwright file is missing: $relative"
        }
        if ((Get-FileSha256 $source) -ne (Get-FileSha256 $target)) {
            throw "Copied Playwright file hash differs: $relative"
        }
    }
}

function Get-FixedBrowserProcesses([string]$ExpectedRoot) {
    $rootPrefix = [IO.Path]::GetFullPath($ExpectedRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    return @(Get-Process -Name 'chrome', 'chrome-headless-shell' `
            -ErrorAction SilentlyContinue | ForEach-Object {
            try {
                $path = $_.Path
                if ($path -and [IO.Path]::GetFullPath($path).StartsWith(
                        $rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                    [pscustomobject]@{ Id = $_.Id; Path = $path }
                }
            } catch {}
        })
}

function Start-BrowserPathObserver([string]$ExpectedRoot) {
    return Start-Job -ArgumentList $ExpectedRoot -ScriptBlock {
        param([string]$Root)
        $rootPrefix = [IO.Path]::GetFullPath($Root).TrimEnd(
            [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        $seen = @{}
        $deadline = [DateTime]::UtcNow.AddSeconds(90)
        while ([DateTime]::UtcNow -lt $deadline) {
            foreach ($process in Get-Process -Name 'chrome', 'chrome-headless-shell' `
                    -ErrorAction SilentlyContinue) {
                try {
                    $path = $process.Path
                    $key = "$($process.Id)|$path"
                    if ($path -and -not $seen.ContainsKey($key) -and
                        [IO.Path]::GetFullPath($path).StartsWith(
                            $rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                        $seen[$key] = $true
                        [pscustomobject]@{
                            ProcessId = $process.Id
                            ExecutablePath = $path
                            ObservedAt = [DateTimeOffset]::Now.ToString('o')
                        }
                    }
                } catch {}
            }
            Start-Sleep -Milliseconds 50
        }
    }
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
    $activeUrlLine = $lines | Where-Object {
        $_ -match '^\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
    } | Select-Object -First 1
    $useCommented = $null -eq $activeUrlLine
    $urlLine = if ($useCommented) {
        $lines | Where-Object {
            $_ -match '^\s*#\s*url\s*:\s*.*\/nexus_test(?:\?|\s|$)'
        } | Select-Object -First 1
    } else { $activeUrlLine }
    $settingPrefix = if ($useCommented) { '^\s*#\s*' } else { '^\s*' }
    $userLine = $lines | Where-Object {
        $_ -match ($settingPrefix + 'username\s*:')
    } | Select-Object -First 1
    $passwordLine = $lines | Where-Object {
        $_ -match ($settingPrefix + 'password\s*:')
    } | Select-Object -First 1
    if (-not $urlLine -or -not $userLine -or -not $passwordLine) {
        throw 'Dedicated nexus_test URL, username, or password is missing.'
    }

    $url = Get-YamlScalar $urlLine 'url' $useCommented
    $username = Get-YamlScalar $userLine 'username' $useCommented
    $password = Get-YamlScalar $passwordLine 'password' $useCommented
    if ($url -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/nexus_test(?:\?|$)' -or
        $username -ne 'nexus_test_user' -or
        [string]::IsNullOrWhiteSpace($password)) {
        throw 'Selected database settings are not the dedicated local nexus_test account.'
    }
    return [pscustomobject]@{
        Url = $url
        Username = $username
        Password = $password
        Source = if ($useCommented) { 'saved-commented-test-settings' } else { 'active-test-settings' }
    }
}

function New-RandomSecret([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Assert-PortUnused([int]$Port) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
        throw "Port $Port is already in use; no process or deployment state was changed."
    }
}

function Wait-Backend([Diagnostics.Process]$Process, [int]$Port, [int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "Candidate Java process exited before opening port $Port."
        }
        $listener = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)
        if ($listener.Count -gt 0) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "Candidate did not open 127.0.0.1:$Port within $Seconds seconds."
}

function Invoke-HealthCheck([int]$Port) {
    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    try {
        $response = $client.GetAsync(
            "http://127.0.0.1:$Port/v1/utils/uuid").GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $json = $body | ConvertFrom-Json
            return [pscustomobject]@{
                Status = [int]$response.StatusCode
                Code = $json.code
                MediaType = $response.Content.Headers.ContentType.MediaType
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $client.Dispose()
    }
}

function Get-CurrentTarget {
    $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $null }
    if ($item.LinkType -ne 'Junction') {
        throw 'Existing current path is not a directory junction.'
    }
    return @($item.Target)[0]
}

function Remove-CurrentJunction {
    $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) { return }
    if ($item.LinkType -ne 'Junction' -or
        [IO.Path]::GetFullPath($item.FullName) -ne [IO.Path]::GetFullPath($currentPath)) {
        throw 'Refusing to remove an unexpected current path.'
    }
    Remove-Item -LiteralPath $currentPath -Force
}

function Set-CurrentTarget([string]$Target) {
    $fullTarget = [IO.Path]::GetFullPath($Target)
    Assert-PathUnderRoot $fullTarget $releaseRoot
    if (-not (Test-Path -LiteralPath $fullTarget -PathType Container)) {
        throw "Current target does not exist: $fullTarget"
    }
    Remove-CurrentJunction
    New-Item -ItemType Junction -Path $currentPath -Target $fullTarget | Out-Null
}

function Save-ConfigSnapshot {
    if (-not (Test-Path -LiteralPath $activeConfigPath -PathType Leaf)) {
        throw "Active production configuration is missing: $activeConfigPath"
    }
    if (Test-Path -LiteralPath $configSnapshotPath -PathType Leaf) {
        if ((Get-FileSha256 $activeConfigPath) -ne
            (Get-FileSha256 $configSnapshotPath)) {
            throw 'Existing release configuration snapshot differs from the active configuration.'
        }
        return
    }
    $snapshotRoot = Split-Path -Parent $configSnapshotPath
    Assert-PathUnderRoot $snapshotRoot $deployRootPath
    New-Item -ItemType Directory -Path $snapshotRoot -Force | Out-Null
    [IO.File]::WriteAllBytes($configSnapshotPath,
        [IO.File]::ReadAllBytes($activeConfigPath))
    (Get-Item -LiteralPath $configSnapshotPath).IsReadOnly = $true
}

if (-not (Test-Path -LiteralPath $deployRootPath -PathType Container)) {
    throw "Deployment root does not exist: $deployRootPath"
}
Assert-PathUnderRoot $releasePath $deployRootPath
if (-not (Test-Path -LiteralPath $releasePath -PathType Container)) {
    throw "Candidate release does not exist: $releasePath"
}
Assert-PortUnused $BackendPort
$database = Get-TestDatabaseSettings
$manifest = Assert-ReleaseManifest
Assert-CurrentBuildMatchesRelease
Assert-BrowserCopy
if ($VerifyScreenshotBrowserPath) {
    if (-not (Test-Path -LiteralPath $screenshotDiagnosticPath -PathType Leaf)) {
        throw "Screenshot diagnostic script is missing: $screenshotDiagnosticPath"
    }
    $existingFixedBrowsers = Get-FixedBrowserProcesses $browserRoot
    if ($existingFixedBrowsers.Count -ne 0) {
        throw 'A browser from the fixed Playwright directory is already running; observation would be ambiguous.'
    }
}
Write-Output (("Integrity: release={0} artifacts={1} jarAndDistMatchBuild=true " +
        "browserExecutablesMatchCache=true readOnly=true") -f `
        $ReleaseName, @($manifest.artifacts).Count)
Write-Output "Database preflight: schema=nexus_test account=dedicated source=$($database.Source)"

if (-not $JavaExe) {
    $javaHomeCandidate = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else { '' }
    if ($javaHomeCandidate -and
        (Test-Path -LiteralPath $javaHomeCandidate -PathType Leaf)) {
        $JavaExe = $javaHomeCandidate
    } elseif (Test-Path -LiteralPath 'D:\java21\bin\java.exe' -PathType Leaf) {
        $JavaExe = 'D:\java21\bin\java.exe'
    } else {
        $JavaExe = (Get-Command java -ErrorAction Stop).Source
    }
}

$previousCurrentTarget = Get-CurrentTarget
$backendProcess = $null
$passed = $false
$environmentNames = @(
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
    'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE',
    'NEXUS_JWT_SECRET',
    'NEXUS_LOG_DIR',
    'PLAYWRIGHT_BROWSERS_PATH'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
        $name, 'Process')
}

try {
    Set-CurrentTarget $releasePath
    New-Item -ItemType Directory -Path $acceptanceRoot,
        $applicationLogRoot -Force | Out-Null

    $env:SPRING_DATASOURCE_URL = $database.Url
    $env:SPRING_DATASOURCE_USERNAME = $database.Username
    $env:SPRING_DATASOURCE_PASSWORD = $database.Password
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '2'
    $env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '1'
    $env:NEXUS_JWT_SECRET = New-RandomSecret 32
    $env:NEXUS_LOG_DIR = $applicationLogRoot
    $env:PLAYWRIGHT_BROWSERS_PATH = $browserRoot

    $localConfigUri = $serverRoot.Replace('\', '/') + '/config/'
    $jarPath = Join-Path $currentPath 'server\nexus-server.jar'
    $javaArguments = @(
        '-jar',
        ('"' + $jarPath + '"'),
        '--spring.profiles.active=local',
        "--spring.config.additional-location=file:$localConfigUri",
        '--server.address=127.0.0.1',
        "--server.port=$BackendPort"
    )
    if ($InjectMissingConfigFailure) {
        $missingConfigPath = Join-Path $acceptanceRoot 'intentionally-missing.yml'
        $missingConfigUri = $missingConfigPath.Replace('\', '/')
        $javaArguments += "--spring.config.import=file:$missingConfigUri"
        Write-Output 'Failure injection: required external configuration is intentionally missing.'
    }
    $backendProcess = Start-Process -FilePath $JavaExe -ArgumentList $javaArguments `
        -WorkingDirectory $deployRootPath -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $standardOutputPath `
        -RedirectStandardError $standardErrorPath

    Wait-Backend $backendProcess $BackendPort 90
    Start-Sleep -Milliseconds 300
    $startup = Get-Content -LiteralPath $standardOutputPath -Raw
    if ($startup -notmatch 'Database: jdbc:mysql://127\.0\.0\.1:3306/nexus_test' -or
        $startup -notmatch "Tomcat started on port $BackendPort\b") {
        throw 'Startup log did not prove nexus_test and the isolated backend port.'
    }

    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $BackendPort `
        -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or
        $listeners[0].LocalAddress -ne '127.0.0.1') {
        throw 'Candidate was not exclusively bound to the expected IPv4 loopback address.'
    }

    $health = Invoke-HealthCheck $BackendPort
    if ($health.Status -ne 401 -or
        $health.Code -ne 'INVALID_API_KEY_CREDENTIAL' -or
        $health.MediaType -ne 'application/json') {
        throw 'HTTP health check did not reach the expected machine authentication boundary.'
    }

    $browserObservations = @()
    if ($VerifyScreenshotBrowserPath) {
        $observerJob = Start-BrowserPathObserver $browserRoot
        try {
            Start-Sleep -Milliseconds 500
            & $screenshotDiagnosticPath `
                -StartupLogPath $standardOutputPath `
                -BaseUri "http://127.0.0.1:$BackendPort" `
                -ArticleUrl $ScreenshotArticleUrl `
                -RequireSuccess
        } finally {
            Start-Sleep -Milliseconds 250
            if ($observerJob.State -eq 'Running') {
                Stop-Job -Job $observerJob -ErrorAction SilentlyContinue
            }
            $browserObservations = @(Receive-Job -Job $observerJob `
                -ErrorAction SilentlyContinue)
            Remove-Job -Job $observerJob -Force -ErrorAction SilentlyContinue
        }
        $observedPaths = @($browserObservations |
            Select-Object -ExpandProperty ExecutablePath -Unique)
        if ($observedPaths.Count -eq 0) {
            throw 'Screenshot returned without observing a browser process in the fixed Playwright directory.'
        }
        foreach ($path in $observedPaths) {
            Write-Output "Observed fixed browser executable: $path"
        }
    }

    Save-ConfigSnapshot
    $previousSuccessful = if (Test-Path -LiteralPath $lastSuccessfulPath) {
        (Get-Content -LiteralPath $lastSuccessfulPath -Raw).Trim()
    } else { $null }
    if (-not [string]::IsNullOrWhiteSpace($previousSuccessful) -and
        $previousSuccessful -ne $ReleaseName) {
        Write-Utf8Text $rollbackReleasePath "$previousSuccessful`n"
    }
    Write-Utf8Text $lastSuccessfulPath "$ReleaseName`n"
    $rollbackRelease = if (Test-Path -LiteralPath $rollbackReleasePath) {
        (Get-Content -LiteralPath $rollbackReleasePath -Raw).Trim()
    } else { $null }
    Write-Utf8Text (Join-Path $acceptanceRoot 'result.txt') (
        "PASS release=$ReleaseName profile=local schema=nexus_test " +
        "bind=127.0.0.1:$BackendPort http=401 code=$($health.Code) " +
        "screenshotBrowserVerified=$VerifyScreenshotBrowserPath`n")

    $passed = $true
    Write-Output "Candidate: current -> $ReleaseName"
    Write-Output "Startup: workingDirectory=$deployRootPath profile=local schema=nexus_test bind=127.0.0.1:$BackendPort"
    Write-Output "HTTP health: status=$($health.Status) code=$($health.Code) mediaType=$($health.MediaType)"
    Write-Output "Browser path: $browserRoot"
    Write-Output "State: lastSuccessful=$ReleaseName rollback=$(if ([string]::IsNullOrWhiteSpace($rollbackRelease)) { '<none>' } else { $rollbackRelease })"
    Write-Output "PASS: real release integrity, startup, HTTP health, and acceptance succeeded. Logs=$acceptanceRoot"
} finally {
    if ($null -ne $backendProcess) {
        try {
            if (-not $backendProcess.HasExited) {
                $backendProcess.Kill()
                $backendProcess.WaitForExit(10000) | Out-Null
            }
        } catch {}
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name, $previousEnvironment[$name], 'Process')
    }
    if (-not $passed) {
        try {
            if ($null -eq $previousCurrentTarget) {
                Remove-CurrentJunction
            } else {
                Set-CurrentTarget $previousCurrentTarget
            }
        } catch {
            Write-Output "ROLLBACK-ERROR: $($_.Exception.Message)"
        }
        Write-Output "FAIL: candidate was not promoted. Inspect $acceptanceRoot"
    }
}
