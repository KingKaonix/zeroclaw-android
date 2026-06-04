# ZeroClaw Android 🦀📱

**Full ZeroClaw AI agent runtime on Android. On-device, private, monetizable.**

Fork of [zeroclaw-labs/zeroclaw](https://github.com/zeroclaw-labs/zeroclaw) cross-compiled for ARM64 Android and wrapped in a minimal foreground service + WebView dashboard.

## Quick Start

```bash
# Prerequisites: Rust, Android NDK 27+, Git
git clone https://github.com/KingKaonix/zeroclaw-android.git
cd zeroclaw-android
./scripts/bootstrap.ps1    # Install targets + NDK config
./scripts/build.ps1        # Build everything
```

Output APK: `app/build/outputs/apk/release/zeroclaw-release.apk`

## Architecture

```
┌──────────────────────────────────┐
│  WebView Dashboard (localhost)    │  ← ZeroClaw's built-in web UI
│  http://127.0.0.1:18789           │
├──────────────────────────────────┤
│  Android Foreground Service       │  ← Keeps agent alive
│  (subprocess: libzeroclaw_core)   │
├──────────────────────────────────┤
│  libzeroclaw_core.so              │  ← Cross-compiled Rust binary
│  (Rust agent runtime for ARM64)   │
└──────────────────────────────────┘
```

## Revenue Model

| Tier | Price | Features |
|------|-------|----------|
| Free | $0 | Basic agent + 1 channel + 3 skills |
| Pro | $8/mo | All channels + skills + managed LLM |
| Enterprise | $20/mo | White-label + custom + SLA |

See [docs/MONETIZATION.md](docs/MONETIZATION.md) for full strategy.

## Build Requirements

- **Rust** 1.80+ with `aarch64-linux-android` target
- **Android NDK** 27+ (via Android Studio or standalone)
- **Java 17+** (for APK packaging)
- **Git LFS** (for binary artifacts)

## Project Structure

```
zeroclaw-android/
├── README.md
├── BUILD.md                 # Full build guide for handoff
├── docs/
│   ├── ARCHITECTURE.md      # System design
│   └── MONETIZATION.md      # Revenue strategy
├── scripts/
│   ├── bootstrap.ps1        # Environment setup (Win)
│   ├── bootstrap.sh         # Environment setup (Linux/Mac)
│   ├── build.ps1            # Full build pipeline (Win)
│   └── build.sh             # Full build pipeline (Linux/Mac)
├── native/
│   ├── Cargo.toml           # Rust JNI bridge crate
│   └── src/
│       └── lib.rs           # JNI entry point
├── app/
│   ├── build.gradle.kts     # Android app module
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/kaonixx/zeroclaw/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ZeroClawService.kt
│   │   │   └── LicenseValidator.kt
│   │   └── res/
│   └── ...
├── zeroclaw/                # Git submodule → git@github.com:zeroclaw-labs/zeroclaw.git
└── .github/workflows/
    ├── build.yml            # CI build pipeline
    └── release.yml          # Release automation
```

## License

Proprietary. See LICENSE file.
