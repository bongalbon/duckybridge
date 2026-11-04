$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    if (-not (Test-Path Cargo.lock)) { cargo generate-lockfile; if ($LASTEXITCODE -ne 0) { throw 'Résolution Cargo en échec' } }
    cargo fmt --all --check
    if ($LASTEXITCODE -ne 0) { throw 'Formatage Rust en échec' }
    cargo clippy --all-targets --locked -- -D warnings
    if ($LASTEXITCODE -ne 0) { throw 'Analyse Rust en échec' }
    cargo test --workspace --locked
    if ($LASTEXITCODE -ne 0) { throw 'Tests Rust en échec' }
    Push-Location android-app
    try {
        & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
        if ($LASTEXITCODE -ne 0) { throw 'Validation Android en échec' }
    } finally { Pop-Location }
} finally { Pop-Location }
