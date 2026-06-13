package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ZeroClawService : Service() {

    private var process: Process? = null
    private val agentExecutor = Executors.newSingleThreadExecutor()
    private var notificationScheduler: ScheduledExecutorService? = null
    private val CONFIG_DIR = ".zeroclaw"
    private val GATEWAY_PORT = 18789

    companion object {
        const val TAG = "SimonAI"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "simonai_service"
        const val CHANNEL_NAME = "SimonAI Agent"
        const val ACTION_STOP = "com.kaonixx.zeroclaw.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start failed: ${e.message}")
        }
        startAgent()
        startNotificationUpdater()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SimonAI agent service notification"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val isPro = LicenseValidator.isPro(this)
        val tier = if (isPro) "Pro" else "Free"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SimonAI $tier")
            .setContentText("Agent running on :$GATEWAY_PORT")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Exit",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, ZeroClawService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    private fun startAgent() {
        agentExecutor.execute {
            try {
                val nativeLibDir = File(applicationInfo.nativeLibraryDir)
                val nativeBinary = File(nativeLibDir, "libzeroclaw.so")
                if (!nativeBinary.exists()) {
                    Log.e(TAG, "Native library not found at ${nativeBinary.absolutePath}")
                    showFailedNotification("Binary not found")
                    return@execute
                }
                Log.i(TAG, "Found native library at ${nativeBinary.absolutePath}")

                // Copy binary to cacheDir so our JNI code can read it cleanly.
                // The memfd approach copies it into anonymous executable memory,
                // bypassing the noexec mount flag on app data directories.
                val cachedBinary = File(cacheDir, "libzeroclaw.exec")
                if (!cachedBinary.exists() || cachedBinary.lastModified() < nativeBinary.lastModified()) {
                    try {
                        nativeBinary.inputStream().use { input ->
                            cachedBinary.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        cachedBinary.setExecutable(true)
                        Log.i(TAG, "Binary cached at ${cachedBinary.absolutePath} " +
                                "(${cachedBinary.length()} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cache binary, using native dir: ${e.message}")
                    }
                }

                // Use cached copy if available (writable), fall back to native dir
                val binaryFile = if (cachedBinary.exists()) cachedBinary else nativeBinary

                // Set up config directory and generate default config if needed
                val configDir = File(filesDir, CONFIG_DIR)
                if (!configDir.exists()) configDir.mkdirs()

                val configFile = File(configDir, "config.toml")
                if (!configFile.exists()) {
                    Log.i(TAG, "Writing default config to ${configFile.absolutePath}")
                    writeDefaultConfig(configFile)
                }

                // Ensure gateway port is set correctly
                applyConfigOverride(configFile, "gateway", "port", GATEWAY_PORT.toString())
                applyConfigOverride(configFile, "gateway", "web_dist_dir",
                    "${filesDir.absolutePath}/web/dist")

                Log.i(TAG, "Config setup complete")

                // Start the daemon via memfd execution (bypasses noexec)
                val daemonArgs = listOf(
                    "--config-dir", configDir.absolutePath,
                    "daemon", "-p", GATEWAY_PORT.toString()
                )

                process = NativeExecutor.exec(
                    binaryFile, daemonArgs, filesDir,
                    mapOf("RUST_LOG" to "info", "RUST_BACKTRACE" to "1")
                )

                if (process == null) {
                    Log.e(TAG, "Failed to start daemon via NativeExecutor")
                    showFailedNotification("All execution strategies failed")
                    return@execute
                }

                Log.i(TAG, "Agent daemon started")
                Log.i(TAG, "Gateway: http://127.0.0.1:$GATEWAY_PORT")

                // Wait for process to exit (blocks this executor thread)
                try {
                    val exitCode = process?.waitFor()
                    Log.w(TAG, "Agent daemon exited with code: $exitCode")
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for daemon", e)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Agent crashed", e)
                showFailedNotification(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Write a minimal default TOML config so the daemon can start without
     * needing to run `binary config generate` (which would require exec
     * just for a quick one-shot).
     */
    private fun writeDefaultConfig(configFile: File) {
        try {
            configFile.writeText("""
                [gateway]
                port = $GATEWAY_PORT
                web_dist_dir = "${filesDir.absolutePath}/web/dist"

                [agents.default]
                enabled = true

                [mcp]
                enabled = true

                [memory]
                backend = "sqlite"

                [logs]
                level = "info"

                [server]
                allowed_origins = ["http://localhost:18789", "http://127.0.0.1:18789", "capacitor://localhost", "file://"]

                [auth]
                mode = "optional"
            """.trimIndent())
            Log.i(TAG, "Default config written")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default config: ${e.message}")
        }
    }

    /**
     * Apply a config override by writing to the TOML file.
     * Simple approach: add or replace a key under a section.
     */
    private fun applyConfigOverride(configFile: File, section: String, key: String, value: String) {
        try {
            if (!configFile.exists()) return
            val lines = configFile.readLines().toMutableList()
            val sectionHeader = "[$section]"
            val keyValue = "$key = \"$value\""

            // Find section and update or append
            var sectionIndex = -1
            var keyIndex = -1

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line == sectionHeader) sectionIndex = i
                if (sectionIndex >= 0 && line.startsWith("$key =")) {
                    keyIndex = i
                    break
                }
            }

            if (keyIndex >= 0) {
                // Update existing key
                lines[keyIndex] = keyValue
            } else if (sectionIndex >= 0) {
                // Add key after section header
                lines.add(sectionIndex + 1, keyValue)
            } else {
                // Add section and key
                lines.add("")
                lines.add(sectionHeader)
                lines.add(keyValue)
            }

            configFile.writeText(lines.joinToString("\n") + "\n")
            Log.d(TAG, "Config override: $section.$key = $value")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply config override $section.$key: ${e.message}")
        }
    }

    private fun showFailedNotification(detail: String = "Check logs") {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            manager.notify(NOTIFICATION_ID + 1, builder
                .setContentTitle("SimonAI Failed")
                .setContentText("Agent daemon could not start: $detail")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .build())
        } catch (_: Exception) {}
    }

    private fun startNotificationUpdater() {
        notificationScheduler = Executors.newSingleThreadScheduledExecutor()
        notificationScheduler?.scheduleAtFixedRate({
            try {
                val notification = buildNotification()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }, 60L, 60L, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationScheduler?.shutdown()
        process?.destroy()
        try { process?.waitFor() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
