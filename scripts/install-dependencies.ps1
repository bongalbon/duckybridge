[CmdletBinding()]
param([switch]$CheckOnly)

. (Join-Path $PSScriptRoot 'windows-common.ps1')
try {
    Initialize-BuildEnvironment
    $rustup = Join-Path $env:CARGO_HOME 'bin\rustup.exe'
    $cargo = Join-Path $env:CARGO_HOME 'bin\cargo.exe'
    if ($CheckOnly) {
        $toolchainReady = $false
        if (Test-Path -LiteralPath $rustup) {
            $installed = & $rustup toolchain list
            $toolchainReady = $LASTEXITCODE -eq 0 -and [bool]($installed -match "^stable-$script:WindowsRustTarget(?:\s|$)")
        }
        $checks = [ordered]@{
            'Java 17' = $script:JavaReady
            'Android SDK 36' = (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'))
            'Android Build Tools 35' = (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt.exe'))
            'Android Build Tools 36' = (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt2.exe'))
            'Visual C++ x64' = [bool](Get-VisualCppPath)
            'Windows SDK x64' = (Test-WindowsSdk)
            'Rust stable MSVC x64' = $toolchainReady
        }
        foreach ($entry in $checks.GetEnumerator()) { Write-Host ("{0}: {1}" -f $entry.Key, $(if ($entry.Value) { 'OK' } else { 'MISSING' })) }
        if ($checks.Values -contains $false) { exit 1 }
        exit 0
    }

    Write-Host 'Installing build prerequisites and downloading project dependencies.'
    Write-Host 'Package installation accepts the dependency licences. Windows may display an installer/UAC prompt.'
    if (-not $script:JavaReady) {
        Invoke-BuildTool -File 'winget.exe' -Arguments @('install', '--id', 'EclipseAdoptium.Temurin.17.JDK', '--exact', '--source', 'winget', '--accept-source-agreements', '--accept-package-agreements')
        Initialize-BuildEnvironment
        if (-not $script:JavaReady) { throw 'Java 17 not detected after installation. Reopen PowerShell and run this script again.' }
    }

    if (-not (Get-VisualCppPath) -or -not (Test-WindowsSdk)) {
        $installerDir = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer'
        $vswhere = Join-Path $installerDir 'vswhere.exe'
        $existing = $null
        if (Test-Path -LiteralPath $vswhere) {
            $existing = & $vswhere -latest -products Microsoft.VisualStudio.Product.BuildTools -property installationPath
        }
        if ($existing) {
            $process = Start-Process -FilePath (Join-Path $installerDir 'setup.exe') -ArgumentList @('modify', '--installPath', ('"' + ($existing | Select-Object -First 1) + '"'), '--add', 'Microsoft.VisualStudio.Workload.VCTools', '--includeRecommended', '--passive', '--norestart') -WindowStyle Hidden -Wait -PassThru
            if ($process.ExitCode -eq 3010) { throw 'C++ tools installed. Restart Windows, then run this script again.' }
            if ($process.ExitCode -ne 0) { throw "Visual C++ installation failed: $($process.ExitCode)" }
        } else {
            Invoke-BuildTool -File 'winget.exe' -Arguments @('install', '--id', 'Microsoft.VisualStudio.2022.BuildTools', '--exact', '--source', 'winget', '--accept-source-agreements', '--accept-package-agreements', '--override', '--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended --norestart')
        }
        if (-not (Get-VisualCppPath) -or -not (Test-WindowsSdk)) { throw 'Visual C++ tools or Windows SDK not detected. Reopen PowerShell or restart Windows if requested, then retry.' }
    }

    $sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
    $packages = @('platform-tools', 'platforms;android-36', 'build-tools;35.0.0', 'build-tools;36.0.0')
    $sdkComplete = (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')) -and (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar')) -and (Test-Path (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt.exe')) -and (Test-Path (Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt2.exe'))
    if (-not $sdkComplete) {
        if (-not (Test-Path -LiteralPath $sdkmanager)) {
            # Discover the Windows archive from Google's official repository manifest.
            $downloads = Join-Path $script:RepositoryRoot '.tools\android-sdk-download'
            New-Item -ItemType Directory -Force -Path $downloads | Out-Null
            $manifestPath = Join-Path $downloads 'repository.xml'
            Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/repository2-1.xml' -OutFile $manifestPath -TimeoutSec 180
            [xml]$manifest = Get-Content -LiteralPath $manifestPath -Raw
            $package = $manifest.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;latest']")
            if (-not $package) { throw 'Android command line tools not found in the official manifest.' }
            $archive = $package.SelectSingleNode("./*[local-name()='archives']/*[local-name()='archive'][*[local-name()='host-os']='windows']/*[local-name()='complete']")
            if (-not $archive) { throw 'Windows Android SDK archive not found.' }
            $url = $archive.SelectSingleNode("./*[local-name()='url']").InnerText
            $checksum = $archive.SelectSingleNode("./*[local-name()='checksum']")
            $algorithm = $checksum.GetAttribute('type').Replace('-', '').ToUpperInvariant()
            if (-not $algorithm -and $checksum.InnerText.Trim() -match '^[a-fA-F0-9]{40}$') { $algorithm = 'SHA1' }
            if ($algorithm -notin @('SHA1', 'SHA256', 'SHA512')) { throw 'Unsupported Android archive checksum.' }
            $zip = Join-Path $downloads 'commandlinetools.zip'
            Invoke-WebRequest -UseBasicParsing -Uri ("https://dl.google.com/android/repository/" + $url) -OutFile $zip -TimeoutSec 600
            if ((Get-FileHash -LiteralPath $zip -Algorithm $algorithm).Hash -ne $checksum.InnerText.Trim()) { throw 'Android SDK archive checksum mismatch.' }
            $extract = Join-Path $downloads ([guid]::NewGuid().ToString())
            Expand-Archive -LiteralPath $zip -DestinationPath $extract
            $latest = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest'
            New-Item -ItemType Directory -Force -Path $latest | Out-Null
            Get-ChildItem -LiteralPath (Join-Path $extract 'cmdline-tools') | Copy-Item -Destination $latest -Recurse -Force
        }
        1..100 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --licenses
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK licence acceptance failed.' }
        Invoke-BuildTool -File $sdkmanager -Arguments (@("--sdk_root=$env:ANDROID_HOME") + $packages)
    }

    if (-not (Test-Path -LiteralPath $rustup)) {
        $bootstrap = Join-Path $script:RepositoryRoot '.tools\rustup-init.exe'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe' -OutFile $bootstrap -TimeoutSec 600
        $sum = Invoke-WebRequest -UseBasicParsing -Uri 'https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe.sha256' -TimeoutSec 180
        $expected = ($sum.Content.Trim() -split '\s+')[0]
        if ((Get-FileHash -LiteralPath $bootstrap -Algorithm SHA256).Hash -ne $expected) { throw 'rustup checksum mismatch.' }
        Invoke-BuildTool -File $bootstrap -Arguments @('-y', '--no-modify-path', '--profile', 'minimal', '--default-host', $script:WindowsRustTarget, '--default-toolchain', 'none')
    }
    Invoke-BuildTool -File $rustup -Arguments @('toolchain', 'install', "stable-$script:WindowsRustTarget", '--profile', 'minimal')
    Push-Location $script:RepositoryRoot
    try { Invoke-BuildTool -File $cargo -Arguments @("+stable-$script:WindowsRustTarget", 'fetch', '--target', $script:WindowsRustTarget) }
    finally { Pop-Location }

    $init = Join-Path $PSScriptRoot 'gradle\download-dependencies.gradle'
    Invoke-AndroidGradle -Tasks @('-I', $init, ':app:downloadBuildDependencies')
    Write-Host 'Dependencies ready. Run build-apk.ps1 and build-agent.ps1.' -ForegroundColor Green
} catch { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
