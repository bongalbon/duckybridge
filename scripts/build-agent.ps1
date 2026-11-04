[CmdletBinding()]
param([switch]$SkipChecks)

. (Join-Path $PSScriptRoot 'windows-common.ps1')
try {
    Initialize-BuildEnvironment
    $cargo = Join-Path $env:CARGO_HOME 'bin\cargo.exe'
    if (-not (Test-Path -LiteralPath $cargo) -or -not (Get-VisualCppPath) -or -not (Test-WindowsSdk)) {
        throw 'Rust, Visual C++ or Windows SDK missing. Run scripts\install-dependencies.ps1 first.'
    }
    Push-Location $script:RepositoryRoot
    try {
        $toolchain = "+stable-$script:WindowsRustTarget"
        if (-not $SkipChecks) { Invoke-BuildTool -File $cargo -Arguments @($toolchain, 'test', '--workspace', '--target', $script:WindowsRustTarget) }
        else { Write-Host 'Rust tests skipped for this build.' -ForegroundColor Yellow }
        Invoke-BuildTool -File $cargo -Arguments @($toolchain, 'build', '--release', '--package', 'duckybridge-agent', '--target', $script:WindowsRustTarget)
    } finally { Pop-Location }
    $targetRoot = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $script:RepositoryRoot 'target' }
    if (-not [System.IO.Path]::IsPathRooted($targetRoot)) { $targetRoot = Join-Path $script:RepositoryRoot $targetRoot }
    Publish-BuildArtifact -Source (Join-Path $targetRoot "$script:WindowsRustTarget\release\duckybridge-agent.exe") -Name 'agent_duckybridge_x64.exe'
} catch { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
