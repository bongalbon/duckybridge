#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f Cargo.lock ]; then cargo generate-lockfile; fi
cargo fmt --all --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --workspace --locked
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
