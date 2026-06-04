# BUILD.md — Full Build Guide

## For another agent to take over seamlessly

This document contains every command, config, and quirk needed to build ZeroClaw Android from scratch. Follow in order.

---

## 1. Prerequisites

```powershell
# Windows
winget install Rust.Rustup
winget install Google.AndroidStudio
# OR scoop
scoop install rustup android-studio
```

```bash
# Linux/macOS
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
# Download Android Studio manually
```

## 2. Install Rust Android Targets

```powershell
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
```

## 3. Locate or Install Android NDK

```powershell
# Typical install paths
$env:ANDROID_NDK_HOME = "C:\Users\Kaos\AppData\Local\Android\Sdk\ndk\27.0.12077973"
$env:ANDROID_SDK_ROOT  = "C:\Users\Kaos\AppData\Local\Android\Sdk"
```

If NDK not found:

1. Open Android Studio → SDK Manager → SDK Tools
2. Check "NDK (Side by side)" → Apply
3. Or download standalone: https://developer.android.com/ndk/downloads

**NDK 27+ required** for `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` flag (needed by some Rust crates).

## 4. Configure Cargo for Android

Create/edit `C:\Users\Kaos\.cargo\config.toml`:

```toml
[target.aarch64-linux-android]
linker = "C:\\Users\\Kaos\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin\\aarch64-linux-android21-clang.cmd"

[target.armv7-linux-androideabi]
linker = "C:\\Users\\Kaos\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin\\armv7a-linux-androideabi21-clang.cmd"

[target.x86_64-linux-android]
linker = "C:\\Users\\Kaos\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin\\x86_64-linux-android21-clang.cmd"

[target.i686-linux-android]
linker = "C:\\Users\\Kaos\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin\\i686-linux-android21-clang.cmd"
```

## 5. Clone Zeroclaw

```powershell
cd C:\Users\Kaos\.openclaw\workspace\zeroclaw-android
git submodule add https://github.com/zeroclaw-labs/zeroclaw.git zeroclaw
git submodule update --init --recursive
```

## 6. Build zeroclaw for Android

```powershell
# Initial test build (just the core, no features)
cd zeroclaw
cargo build --target aarch64-linux-android --release --no-default-features

# Full build with all features
cargo build --target aarch64-linux-android --release `
  --features "agent-runtime,channel-telegram,channel-discord,channel-webhook,memory-sqlite,tool-shell,tool-http,gateway"

# Verify the binary
file target/aarch64-linux-android/release/zeroclaw  
# Should say: ELF 64-bit LSB shared object, ARM aarch64
```

## 7. Common Build Issues & Fixes

### Issue: OpenSSL linking errors
**Fix:** Use `rustls` instead (pure Rust TLS):
```powershell
cargo build --target aarch64-linux-android --release --features "rustls"
```

### Issue: `cc` can't find compiler
**Fix:** Make sure NDK `bin/` is in PATH:
```powershell
$env:PATH = "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin;$env:PATH"
```

### Issue: `pkg-config` not found
**Fix:** When cross-compiling, many crates try `pkg-config`. Set:
```powershell
$env:PKG_CONFIG_ALLOW_CROSS = "1"
```

### Issue: `getrandom` can't find `/dev/urandom`
**Fix:** Feature flag:
```toml
getrandom = { features = ["wasm-bindgen"] }  # or use "custom" with Android's crypto
```

### Issue: Linker errors about `__cxa_atexit`
**Fix:** Make sure you're using the NDK's `clang` not a system clang. The NDK's clang knows about Android's C++ runtime.

### Issue: `dlopen` / `dlsym` not found
**Fix:** Link `libdl`:
```powershell
# In .cargo/config.toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-ldl"]
```

## 8. Build the Android Wrapper App

```powershell
cd C:\Users\Kaos\.openclaw\workspace\zeroclaw-android

# Copy the compiled binary into app assets
Copy-Item zeroclaw/target/aarch64-linux-android/release/zeroclaw  app/src/main/assets/libzeroclaw.so

# Build APK
./gradlew assembleRelease

# Sign APK
./gradlew signRelease
```

## 9. Manual APK Signing (if gradle fails)

```powershell
# Generate keystore (if you don't have one)
keytool -genkey -v -keystore zeroclaw.keystore `
  -alias zeroclaw -keyalg RSA -keysize 2048 -validity 10000

# Sign APK
"C:\Program Files\Android\Android Studio\jbr\bin\apksigner" sign `
  --ks zeroclaw.keystore `
  --ks-key-alias zeroclaw `
  --out zeroclaw-release-signed.apk `
  app/build/outputs/apk/release/zeroclaw-release-unsigned.apk
```

## 10. Test on Device

```powershell
# Install via ADB
adb install -r zeroclaw-release-signed.apk

# Check service is running
adb shell dumpsys activity services | findstr ZeroClaw

# View logs
adb logcat -s ZeroClaw:D

# Test the WebView dashboard
# Open on device: http://127.0.0.1:18789  (in Chrome, or the in-app WebView)
```

## 11. CI/CD

GitHub Actions workflow is at `.github/workflows/build.yml`.

It will:
1. Install Rust + Android targets
2. Download NDK (via setup-ndk action)
3. Build zeroclaw core for ARM64
4. Build the Android APK
5. Upload APK as artifact

Trigger: `git push` to `main` or manually via workflow_dispatch.

## 12. Release Process

```powershell
./scripts/build.ps1                    # Build fresh binaries
# Test on device manually
# Tag version
git tag v0.1.0
git push origin v0.1.0
# GitHub Action builds + signs + uploads to release
```

For Gumroad: manually download signed APK from GitHub Releases → upload to Gumroad product page.
