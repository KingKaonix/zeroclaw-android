# build.ps1 — Build ZeroClaw Android from scratch
# ./scripts/build.ps1 -Target aarch64-linux-android -Features "agent-runtime,gateway"

param(
    [string]$Target = "aarch64-linux-android",
    [string]$BuildType = "release",
    [string]$Features = "agent-runtime,gateway,memory-sqlite,tool-shell,tool-http"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $Root

Write-Host "=== ZeroClaw Android Build ===" -ForegroundColor Cyan
Write-Host "Target: $Target" -ForegroundColor Yellow
Write-Host "Features: $Features" -ForegroundColor Yellow

# 1. Check prerequisites
if (-not (Get-Command rustc -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: Rust not installed. Run scripts/bootstrap.ps1 first." -ForegroundColor Red
    exit 1
}

$targets = rustup target list --installed
if ($targets -notcontains $Target) {
    Write-Host "Adding target $Target..." -ForegroundColor Yellow
    rustup target add $Target
}

# 2. Ensure zeroclaw submodule
if (-not (Test-Path "$Root\zeroclaw\Cargo.toml")) {
    Write-Host "Cloning zeroclaw..." -ForegroundColor Yellow
    if (-not (Test-Path "$Root\zeroclaw")) { mkdir "$Root\zeroclaw" }
    git clone https://github.com/zeroclaw-labs/zeroclaw.git "$Root\zeroclaw" 2>$null
    if (-not (Test-Path "$Root\zeroclaw\Cargo.toml")) {
        Write-Host "ERROR: Failed to clone zeroclaw" -ForegroundColor Red
        exit 1
    }
}

# 3. Set env vars for cross-compilation
$NDK = $env:ANDROID_NDK_HOME
if (-not $NDK) {
    $ndkDirs = Get-ChildItem "C:\Users\Kaos\AppData\Local\Android\Sdk\ndk" -ErrorAction SilentlyContinue
    if ($ndkDirs) {
        $NDK = $ndkDirs[0].FullName
    }
}
$env:ANDROID_NDK_HOME = $NDK
$env:PKG_CONFIG_ALLOW_CROSS = "1"
$env:PATH = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\bin;$env:PATH"

Write-Host "NDK: $NDK" -ForegroundColor Green

# 4. Build zeroclaw core
Write-Host "`nBuilding zeroclaw for $Target..." -ForegroundColor Yellow
Set-Location "$Root\zeroclaw"

$cargoArgs = @(
    "build",
    "--target", $Target,
    "--$BuildType"
)
if ($Features) {
    $cargoArgs += "--features"
    $cargoArgs += $Features
}
if ($BuildType -eq "release") {
    $cargoArgs += "--no-default-features"
}

Write-Host "cargo $($cargoArgs -join ' ')" -ForegroundColor Gray
cargo $cargoArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED. Check output above." -ForegroundColor Red
    exit 1
}

$binaryPath = "$Root\zeroclaw\target\$Target\$BuildType\zeroclaw"
if (-not (Test-Path $binaryPath)) {
    # Try with .so extension for cdylib crates
    $binaryPath = "$Root\zeroclaw\target\$Target\$BuildType\libzeroclaw_core.so"
}
if (-not (Test-Path $binaryPath)) {
    Write-Host "Binary not found at expected path. Searching..." -ForegroundColor Yellow
    $found = Get-ChildItem "$Root\zeroclaw\target\$Target\$BuildType\" -File | Select-Object -First 5
    $found | ForEach-Object { Write-Host "  $($_.Name)" }
}

Write-Host "`nBuild successful!" -ForegroundColor Green
Write-Host "Binary: $binaryPath" -ForegroundColor Cyan
Write-Host "Size: $( (Get-Item $binaryPath).Length / 1MB ) MB"

# 5. Copy binary to app assets
Set-Location $Root
$assetsDir = "$Root\app\src\main\assets"
if (-not (Test-Path $assetsDir)) { mkdir $assetsDir -Force }
Copy-Item $binaryPath "$assetsDir\libzeroclaw.so" -Force
Write-Host "Copied to app assets" -ForegroundColor Green

Write-Host "`n=== Build Complete ===" -ForegroundColor Cyan
Write-Host "Next: Open in Android Studio and run on device" -ForegroundColor Yellow
