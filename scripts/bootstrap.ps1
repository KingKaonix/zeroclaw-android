# bootstrap.ps1 — Set up Android cross-compilation environment
# Run this once on a fresh machine. Idempotent.

param(
    [string]$NDK_PATH = "C:\Users\Kaos\AppData\Local\Android\Sdk\ndk\27.0.12077973",
    [string]$SDK_PATH = "C:\Users\Kaos\AppData\Local\Android\Sdk"
)

$ErrorActionPreference = "Stop"

Write-Host "=== ZeroClaw Android Bootstrap ===" -ForegroundColor Cyan

# 1. Check Rust
try {
    $ver = rustc --version
    Write-Host "Rust: $ver" -ForegroundColor Green
} catch {
    Write-Host "Installing Rust..." -ForegroundColor Yellow
    # Rustup installer
    $inst = "$env:TEMP\rustup-init.exe"
    Invoke-WebRequest -Uri "https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe" -OutFile $inst
    Start-Process $inst -ArgumentList "-y --profile minimal" -Wait
    $env:Path = [Environment]::GetEnvironmentVariable("Path","User") + ";$env:USERPROFILE\.cargo\bin"
    [Environment]::SetEnvironmentVariable("Path", $env:Path, "User")
    rustc --version
    Write-Host "Rust installed" -ForegroundColor Green
}

# 2. Install Android targets
Write-Host "`nInstalling Android targets..." -ForegroundColor Yellow
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
Write-Host "Targets installed" -ForegroundColor Green

# 3. Check NDK
if (Test-Path $NDK_PATH) {
    Write-Host "NDK found at: $NDK_PATH" -ForegroundColor Green
} else {
    Write-Host "NDK not found at $NDK_PATH" -ForegroundColor Red
    Write-Host "Download from: https://developer.android.com/ndk/downloads" -ForegroundColor Yellow
    Write-Host "Or install via Android Studio: SDK Manager → SDK Tools → NDK (Side by side)" -ForegroundColor Yellow
    exit 1
}

# 4. Configure cargo
$cargoConfig = @"
[target.aarch64-linux-android]
linker = "$NDK_PATH\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd"

[target.armv7-linux-androideabi]
linker = "$NDK_PATH\toolchains\llvm\prebuilt\windows-x86_64\bin\armv7a-linux-androideabi21-clang.cmd"

[target.x86_64-linux-android]
linker = "$NDK_PATH\toolchains\llvm\prebuilt\windows-x86_64\bin\x86_64-linux-android21-clang.cmd"

[target.i686-linux-android]
linker = "$NDK_PATH\toolchains\llvm\prebuilt\windows-x86_64\bin\i686-linux-android21-clang.cmd"
"@

$cargoConfigPath = "$env:USERPROFILE\.cargo\config.toml"
$existing = ""
if (Test-Path $cargoConfigPath) {
    $existing = Get-Content $cargoConfigPath -Raw
}

if ($existing -notmatch "aarch64-linux-android") {
    Add-Content -Path $cargoConfigPath -Value "`n$cargoConfig"
    Write-Host "Cargo config updated" -ForegroundColor Green
} else {
    Write-Host "Cargo config already has Android entries" -ForegroundColor Green
}

# 5. Set environment variables (for this session)
$env:ANDROID_NDK_HOME = $NDK_PATH
$env:ANDROID_SDK_ROOT = $SDK_PATH
$env:PKG_CONFIG_ALLOW_CROSS = "1"
$env:PATH = "$NDK_PATH\toolchains\llvm\prebuilt\windows-x86_64\bin;$env:PATH"

Write-Host "`n=== Bootstrap Complete ===" -ForegroundColor Cyan
Write-Host "Next: ./scripts/build.ps1" -ForegroundColor Yellow
