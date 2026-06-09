#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "=== ZeroClaw Android Build ==="
source "$REPO_ROOT/scripts/bootstrap.sh"
export PATH="$HOME/.cargo/bin:$PATH"

mkdir -p "$REPO_ROOT/app/src/main/assets"

# Try building from source; fall back to pre-built binary if available
if [ -d "$REPO_ROOT/zeroclaw" ]; then
  echo "[2/4] Building zeroclaw for aarch64..."
  cd "$REPO_ROOT/zeroclaw"
  export PKG_CONFIG_ALLOW_CROSS=1
  if cargo build --target aarch64-linux-android --release 2>&1; then
    mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
    cp target/aarch64-linux-android/release/zeroclaw "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libzeroclaw.so"
    echo "[✓] Built from source"
  else
    echo "[!] Source build failed; falling back to pre-built binary"
    if [ -f "$REPO_ROOT/binary/zeroclaw" ]; then
      mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
      cp "$REPO_ROOT/binary/zeroclaw" "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libzeroclaw.so"
      echo "[✓] Using pre-built binary from binary/"
    else
      echo "[✗] No binary available. Build failed."
      exit 1
    fi
  fi
elif [ -f "$REPO_ROOT/binary/zeroclaw" ]; then
  echo "[2/4] Using pre-built binary from binary/..."
  mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
  cp "$REPO_ROOT/binary/zeroclaw" "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libzeroclaw.so"
else
  echo "[✗] No zeroclaw source or binary found. Clone the submodule or add binary/zeroclaw."
  exit 1
fi

echo "[3/4] Building APK..."
cd "$REPO_ROOT"
if [ ! -f gradlew ]; then
  [ -z "${JAVA_HOME:-}" ] && { echo "[!] JAVA_HOME not set; can't generate gradlew"; exit 1; }
  gradle wrapper --gradle-version 8.5
fi
./gradlew assembleRelease --no-daemon
APK=$(find app/build/outputs/apk -name "*.apk" -print -quit)
echo "[4/4] Done!"
echo "[✓] APK: $APK ($(ls -lh "$APK" | awk '{print $5}'))"
echo "Install: adb install -r $APK"
