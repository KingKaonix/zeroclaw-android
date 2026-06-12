# Architecture

## Overview

ZeroClaw Android = zeroclaw Rust binary (cross-compiled to ARM64) + thin Android wrapper. The Rust binary does all the real work. Android keeps it alive and provides a native Compose UI.

## Layers

### Layer 1: Rust Runtime (libzeroclaw_core)

The zeroclaw binary, compiled for `aarch64-linux-android` with features:
- `agent-runtime` — core agent loop
- `gateway` — HTTP/WS server (dashboard + API)
- `memory-sqlite` — SQLite with vector search
- `tool-shell` — command execution (sandboxed)
- `tool-http` — HTTP fetch tool
- `channel-telegram`, `channel-discord`, `channel-webhook` — channel adapters

Runs as a **subprocess** spawned by the Android foreground service.

**Why subprocess not JNI:** JNI crashes in Rust kill the entire app. A subprocess crash just restarts the service. Subprocess = just a binary + stdin/stdout, no JNI naming conventions or env pointer management.

The binary is shipped as `libzeroclaw.so` in `jniLibs/arm64-v8a/` (disguised as a native library for APK packaging), extracted to the app's `nativeLibraryDir` at install time, then copied to `cacheDir` with explicit exec permission for reliable execution across Android versions.

### Layer 2: Android Foreground Service (ZeroClawService.kt)

```kotlin
class ZeroClawService : Service() {
    private var process: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startAgent()
        return START_STICKY
    }

    private fun startAgent() {
        val binary = extractBinary()  // from nativeLibraryDir or cached copy
        val pb = ProcessBuilder(
            binary.absolutePath,
            "--config-dir", configDir.absolutePath,
            "daemon", "-p", GATEWAY_PORT.toString()
        )
        process = pb.start()
    }
}
```

### Layer 3: Compose Native UI

ZeroClaw's REST API runs on `http://127.0.0.1:18789`. The Android app uses a Jetpack Compose UI that talks to this API via `ApiClient`:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, ZeroClawService::class.java))
        setContent {
            ZeroClawTheme {
                when (state) {
                    is Loading -> LoadingScreen()
                    is DaemonError -> DaemonErrorScreen(onRetry = ...)
                    is Ready -> AppShell(isPaired, onPair = ...)
                }
            }
        }
    }
}
```

### Layer 4: Licensing (LicenseValidator.kt)

Offline license verification using ed25519 signatures:
1. On launch, check cached license + signature in SharedPreferences
2. Verify signature against baked-in ed25519 public key
3. Support: native Ed25519 (Android 13+) with BouncyCastle fallback (older devices)
4. If invalid → Free tier with watermark

**Offline grace period:** Keys can include an expiry timestamp. 0 = never expires.

## Data Flow

```
User types message → Compose UI → ApiClient → ZeroClaw Gateway (localhost:18789)
  → zeroclaw runtime → LLM provider (user's key) → Response
  → Gateway → ApiClient → Compose UI → User sees response
```

Channel messages (Telegram/Discord/etc):
```
Telegram message → ZeroClaw Channel adapter
  → zeroclaw runtime processes → Responds via channel API
  → Notification on phone
```

## Filesystem Layout on Device

```
/data/data/com.kaonixx.zeroclaw/
├── cache/
│   └── libzeroclaw.exec        # Executable copy of the binary
├── files/
│   └── .zeroclaw/
│       ├── config.toml         # User's config (editable in-app)
│       ├── memory.db           # SQLite vector database
│       └── receipts/           # Tool call audit logs
└── shared_prefs/
    ├── zeroclaw_license.xml    # License key + signature cache
    └── ...                     # Other prefs
```

## Sandboxing

- **Workspace:** App's internal data directory only
- **Shell commands:** Denied by default. User can whitelist specific commands in config
- **Network:** Full (channels need HTTP/WS)
- **Filesystem:** Android's SELinux + per-process UID provides hardware-level isolation

## Battery

- Agent polling: configurable interval (default 30s)
- Doze mode: Use PowerManager.WakeLock for active processing only
- WorkManager: for scheduled channel checks when app is backgrounded
- Idle detection: pause polling when device is Dozing (check PowerManager.isDeviceIdleMode)
