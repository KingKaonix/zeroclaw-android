#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "=== ZeroClaw Android Build ==="
source "$REPO_ROOT/scripts/bootstrap.sh"
export PATH="$HOME/.cargo/bin:$PATH"
echo "[2/4] Building zeroclaw for aarch64..."
cd "$REPO_ROOT/zeroclaw"
export PKG_CONFIG_ALLOW_CROSS=1
cargo build --target aarch64-linux-android --release --features "rustls"
mkdir -p "$REPO_ROOT/app/src/main/assets"
cp target/aarch64-linux-android/release/zeroclaw "$REPO_ROOT/app/src/main/assets/libzeroclaw.so"
chmod +x "$REPO_ROOT/app/src/main/assets/libzeroclaw.so"
echo "[3/4] Building APK..."
cd "$REPO_ROOT"
[ ! -f gradlew ] && gradle wrapper --gradle-version 8.5
./gradlew assembleRelease
APK=$(find app/build/outputs/apk -name "*.apk" -print -quit)
echo "[✓] APK: $APK ($(ls -lh "$APK" | awk '{print $5}'))"
echo "Install: adb install -r $APK"
