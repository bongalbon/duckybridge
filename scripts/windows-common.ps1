# Shared functions for the three Windows entry points. Compatible with PowerShell 5.1.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:RepositoryRoot = Split-Path -Parent $PSScriptRoot
$script:WindowsRustTarget = 'x86_64-pc-windows-msvc'

function Invoke-BuildTool {
    param([Parameter(Mandatory=$true)][string]$File, [string[]]$Arguments = @())
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed (exit $LASTEXITCODE): $File $($Arguments -join ' ')" }
}

function Initialize-BuildEnvironment {
    $tools = Join-Path $script:RepositoryRoot '.tools'
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $env:CARGO_HOME = Join-Path $tools 'cargo'
    $env:RUSTUP_HOME = Join-Path $tools 'rustup'
    $env:Path = "$env:CARGO_HOME\bin;$env:Path"
    # These options apply only to this PowerShell process and its children.
    $temporaryOption = '-Djdk.net.unixdomain.tmpdir="' + $tools.Replace('\', '/') + '"'
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS $temporaryOption -Djava.net.preferIPv4Stack=true".Trim()
    if (-not $env:ANDROID_HOME) {
        if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
        else { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { $candidates += Split-Path -Parent (Split-Path -Parent $java.Source) }
    $adoptium = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (Test-Path -LiteralPath $adoptium) {
        $candidates += @(Get-ChildItem -LiteralPath $adoptium -Directory -Filter 'jdk-17*' | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    }
    $script:JavaReady = $false
    foreach ($candidate in $candidates) {
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath $release) -and ((Get-Content -LiteralPath $release -Raw) -match 'JAVA_VERSION="17[.\"]')) {
            if (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) {
                $env:JAVA_HOME = $candidate
                $env:Path = "$candidate\bin;$env:Path"
                $script:JavaReady = $true
                break
            }
        }
    }
}

function Get-VisualCppPath {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $result = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($LASTEXITCODE -eq 0 -and $result) { return ($result | Select-Object -First 1) }
    }
    return $null
}

function Test-WindowsSdk {
    $libs = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\Lib'
    if (Test-Path -LiteralPath $libs) {
        foreach ($version in Get-ChildItem -LiteralPath $libs -Directory) {
            if (Test-Path -LiteralPath (Join-Path $version.FullName 'um\x64\kernel32.lib')) { return $true }
        }
    }
    return $false
}

function Get-GradleArguments {
    param([string[]]$Tasks)
    $arguments = @('--no-daemon', '--console=plain', '--stacktrace')
    $aapt = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt2.exe'
    if (Test-Path -LiteralPath $aapt) { $arguments += "-Pandroid.aapt2FromMavenOverride=$aapt" }
    return $arguments + $Tasks
}

function Invoke-AndroidGradle {
    param([string[]]$Tasks)
    if (-not $script:JavaReady) { throw 'Java 17 missing. Run scripts\install-dependencies.ps1 first.' }
    if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'))) {
        throw 'Android SDK 36 missing. Run scripts\install-dependencies.ps1 first.'
    }
    Push-Location (Join-Path $script:RepositoryRoot 'android-app')
    try { Invoke-BuildTool -File '.\gradlew.bat' -Arguments (Get-GradleArguments -Tasks $Tasks) }
    finally { Pop-Location }
}

function Publish-BuildArtifact {
    param([string]$Source, [string]$Name)
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Build output missing: $Source" }
    $dist = Join-Path $script:RepositoryRoot 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $target = Join-Path $dist $Name
    Copy-Item -LiteralPath $Source -Destination $target -Force
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$target.sha256", "$hash  $Name`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "`nBuild completed: $target" -ForegroundColor Green
    Write-Host "SHA-256: $hash"
}
