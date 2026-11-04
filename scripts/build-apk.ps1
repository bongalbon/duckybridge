[CmdletBinding()]
param([switch]$SkipChecks)

. (Join-Path $PSScriptRoot 'windows-common.ps1')
try {
    Initialize-BuildEnvironment
    $tasks = @(':app:assembleDebug')
    if (-not $SkipChecks) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
    else { Write-Host 'Tests and lint skipped for this build.' -ForegroundColor Yellow }
    Invoke-AndroidGradle -Tasks $tasks
    Publish-BuildArtifact -Source (Join-Path $script:RepositoryRoot 'android-app\app\build\outputs\apk\debug\app-debug.apk') -Name 'duckybrige.apk'
} catch { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
