# SimonAI Changelog — 2026-06-09

## [1.0.0-alpha.1] — 2026-06-09

### Branding & UI
- **Rebrand to SimonAI**: Renamed from ZeroClaw to SimonAI.
- **New Logo**: Custom Husky-themed icon for app launcher and web dashboard.
- **Dark Theme**: Premium Linear-inspired dark UI with purple/indigo accents.
- **Landing Page**: Fully redesigned Gumroad landing page with terminal preview and feature grid.

### Features
- **Monetization**: Integrated Gumroad for Pro licenses ($8).
- **Activation Flow**: Built-in activation dialog (Auto via Gumroad Sale ID or Manual Key).
- **Pro Features**: License verification against Cloudflare Workers backend.
- **Improved Android UX**: Fix for W^X policy by shipping binary in `jniLibs`.
- **Permission Handling**: Runtime request for POST_NOTIFICATIONS (Android 13+).

### Fixes
- **Binary Execution**: Fixed "permission denied" by moving native binary to `jniLibs`.
- **Foreground Service**: Fixed `foregroundServiceType` to `dataSync`.
- **Config Drift**: Fixed config synchronization issues between in-memory and disk.
- **Gradle**: Fixed `gradlew` argument passing (`"$APP_ARGS"` -> `"$@"`).
- **Build**: Fixed native build scripts to include all features (agent-runtime, gateway, etc).
