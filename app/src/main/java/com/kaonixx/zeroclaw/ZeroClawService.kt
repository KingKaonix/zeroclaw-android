package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class ZeroClawService : Service() {

    private var process: java.lang.Process? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val CONFIG_DIR = ".zeroclaw"
    private val GATEWAY_PORT = 18789

    companion object {
        const val TAG = "ZeroClaw"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "zeroclaw_service"
        const val CHANNEL_NAME = "ZeroClaw Agent"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start failed: ${e.message}")
        }
        startAgent()
        updateNotificationPeriodically()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ZeroClaw agent service notification"
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZeroClaw $tier")
            .setContentText("Agent running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startAgent() {
        executor.execute {
            try {
                val binary = extractBinary()
                if (binary == null) {
                    Log.e(TAG, "Binary extraction failed")
                    return@execute
                }

                val configDir = File(filesDir, CONFIG_DIR)
                if (!configDir.exists()) configDir.mkdirs()

                // Create default config if not exists
                val configFile = File(configDir, "config.toml")
                if (!configFile.exists()) {
                    configFile.writeText(generateDefaultConfig())
                }

                // Make binary executable
                binary.setExecutable(true)

                val pb = ProcessBuilder(
                    binary.absolutePath,
                    "--config-dir", configDir.absolutePath,
                    "--gateway", "127.0.0.1:$GATEWAY_PORT"
                )
                pb.directory(configDir)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["RUST_LOG"] = "info"

                process = pb.start()

                Log.i(TAG, "Agent started.")
                Log.i(TAG, "Gateway: http://127.0.0.1:$GATEWAY_PORT")

                // Read stdout in background
                process?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        Log.d(TAG, "[agent] $line")
                    }
                }

                // Wait for process
                val exitCode = process?.waitFor()
                Log.w(TAG, "Agent exited with code: $exitCode")

            } catch (e: Exception) {
                Log.e(TAG, "Agent crashed", e)
            }
        }
    }

    private fun extractBinary(): File? {
        val dest = File(filesDir, "libzeroclaw.so")
        if (dest.exists() && dest.length() > 0) return dest

        return try {
            applicationContext.assets.open("libzeroclaw.so").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary from assets", e)
            null
        }
    }

    private fun generateDefaultConfig(): String {
        return """
# ZeroClaw Android - Default Config
# Edit this file to configure your agent.

[agent]
name = "ZeroClaw-Android"
model = "gpt-4o-mini"

[provider]
# Set your API key in the app settings, or use the managed tier
# api_key = "sk-..."

[gateway]
host = "127.0.0.1"
port = $GATEWAY_PORT

[workspace]
# Sandboxed to app data directory
root = "${filesDir.absolutePath.replace("\\", "/")}/workspace"
allowed_paths = ["${filesDir.absolutePath.replace("\\", "/")}"]

[mcp]
# Enable MCP for tool integration
enabled = true

[tools]
# Sandboxed shell execution
[security]
supervised = true
allow_shell = false
allow_filesystem = true

[memory]
backend = "sqlite"
path = "${filesDir.absolutePath.replace("\\", "/")}/.zeroclaw/memory.db"
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        process?.destroy()
        process?.waitFor()
    }

    private fun updateNotificationPeriodically() {
        executor.execute {
            while (process?.isAlive == true) {
                try {
                    val notification = buildNotification()
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                } catch (_: Exception) {}
                Thread.sleep(60_000L) // update every minute
            }
        }
    }

    override fun onBind(intent: Intent?) = null
}
