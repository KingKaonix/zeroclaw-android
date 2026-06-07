#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "=== ZeroClaw Android Bootstrap (Linux/macOS) ==="
if ! command -v rustup &>/dev/null; then
  echo "[*] Installing rustup..."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
fi
export PATH="$HOME/.cargo/bin:$PATH"
rustup target add aarch64-linux-android armv7-linux-androideabi
echo "[✓] Rust + Android targets"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for d in "${ANDROID_HOME:-}/ndk/27.0.12077973" "$HOME/Android/Sdk/ndk/27.0.12077973" "$HOME/apps/android-sdk/ndk/27.0.12077973"; do
    [ -d "$d" ] && ANDROID_NDK_HOME="$d" && break
  done
fi
[ -z "${ANDROID_NDK_HOME:-}" ] && { echo "[!] Set ANDROID_NDK_HOME"; exit 1; }
echo "[✓] NDK: $ANDROID_NDK_HOME"
HOST="linux-x86_64"; [ "$(uname -s)" = "Darwin" ] && HOST="darwin-x86_64"
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin"
mkdir -p ~/.cargo
cat > ~/.cargo/config.toml <<EOF
[target.aarch64-linux-android]
linker = "$NDK_BIN/aarch64-linux-android21-clang"
rustflags = ["-C", "link-arg=-ldl"]
[target.armv7-linux-androideabi]
linker = "$NDK_BIN/armv7a-linux-androideabi21-clang"
[target.x86_64-linux-android]
linker = "$NDK_BIN/x86_64-linux-android21-clang"
EOF
echo "[✓] Cargo config"
