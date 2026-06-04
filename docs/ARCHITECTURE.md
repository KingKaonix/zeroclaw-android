# Architecture

## Overview

ZeroClaw Android = zeroclaw Rust binary (cross-compiled to ARM64) + thin Android wrapper. The Rust binary does all the real work. Android just keeps it alive and shows its web UI.

## Layers

### Layer 1: Rust Runtime (libzeroclaw_core.so)

The zeroclaw binary, compiled for `aarch64-linux-android` with features:

- `agent-runtime` — core agent loop
- `gateway` — HTTP/WS server (dashboard + API)
- `memory-sqlite` — SQLite with vector search
- `tool-shell` — command execution (sandboxed)
- `tool-http` — HTTP fetch tool
- `channel-telegram`, `channel-discord`, `channel-webhook` — channel adapters

Runs as a subprocess spawned by the Android service, not via JNI (simpler, more stable).

**Why subprocess not JNI:** JNI crashes in Rust kill the entire app. A subprocess crash just restarts the service. JNI also adds build complexity (crate type = cdylib, JNI function naming, JNI env pointer passing). Subprocess = just a binary + stdin/stdout.

### Layer 2: Android Foreground Service (ZeroClawService.kt)

```kotlin
class ZeroClawService : Service() {
    private var process: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startZeroClawProcess()
        return START_STICKY
    }

    private fun startZeroClawProcess() {
        val binary = extractBinaryFromAssets()  // Copy libzeroclaw.so to data dir
        val configDir = Files.createDirectories(dataDir.resolve(".zeroclaw"))

        val pb = ProcessBuilder(
            binary.absolutePath,
            "--config-dir", configDir.absolutePath,
            "--gateway", "127.0.0.1:18789"
        )
        process = pb.start()
    }
}
```

### Layer 3: WebView Dashboard

ZeroClaw's built-in web UI runs on `http://127.0.0.1:18789`. The Android app wraps it in a WebView:

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.loadUrl("http://127.0.0.1:18789")
        setContentView(webView)
    }
}
```

### Layer 4: Licensing (LicenseValidator.kt)

Optional phone-home licensing using Cloudflare Workers + Stripe webhooks:

1. On launch, check local JWT (cached in SharedPreferences)
2. If JWT missing or expiring, call `api.zeroclaw.app/verify`
3. Server verifies against Stripe/LemonSqueezy subscription status
4. Returns signed JWT with expiry
5. App verifies JWT signature with baked-in ed25519 public key
6. If invalid → degrade to Free tier

**Offline grace period:** 7 days. App re-checks every 7 days. If no network, last valid JWT counts.

## Data Flow

```
User types message → WebView → ZeroClaw Gateway (localhost:18789)
  → zeroclaw runtime → LLM provider (user's key) → Response
  → Gateway → WebView → User sees response
```

Channel messages (Telegram/Discord/etc):
```
Telegram message → ZeroClaw Channel adapter
  → zeroclaw runtime processes → Responds via channel API
  → Notification on phone (from Android NotificationListener)
```

## Filesystem Layout on Device

```
/data/data/com.kaonixx.zeroclaw/
├── files/
│   └── libzeroclaw.so          # Extracted from APK assets
├── .zeroclaw/
│   ├── config.toml             # User's config (editable in-app)
│   ├── memory.db               # SQLite vector database
│   └── receipts/               # Tool call audit logs
└── shared_prefs/
    └── license.xml             # Cached license JWT
```

## Sandboxing

- **Workspace:** App's internal data directory only
- **Shell commands:** Denied by default. User can whitelist specific commands in config
- **Network:** Full (channels need HTTP/WS)
- **Filesystem:** Android's SELinux + per-process UID provides hardware-level isolation
- **Extra (optional):** `android:isolatedProcess=true` in manifest for the service

## Battery

- Agent polling: configurable interval (default 30s)
- Doze mode: Use PowerManager.WakeLock for active processing only
- WorkManager: for scheduled channel checks when app is backgrounded
- Idle detection: pause polling when device is Dozing (check PowerManager.isDeviceIdleMode)
