package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ZeroClawService : Service() {

    private var process: java.lang.Process? = null
    private val agentExecutor = Executors.newSingleThreadExecutor()
    private var notificationScheduler: ScheduledExecutorService? = null
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
        startNotificationUpdater()
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("ZeroClaw $tier")
            .setContentText("Agent running on :$GATEWAY_PORT")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startAgent() {
        agentExecutor.execute {
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

                val pb = ProcessBuilder(
                    binary.absolutePath,
                    "--config-dir", configDir.absolutePath,
                    "--gateway", "127.0.0.1:$GATEWAY_PORT"
                )
                pb.directory(filesDir)  // working dir must be writable, not binary's dir
                pb.redirectErrorStream(true)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["RUST_LOG"] = "info"

                process = pb.start()

                Log.i(TAG, "Agent started.")
                Log.i(TAG, "Gateway: http://127.0.0.1:$GATEWAY_PORT")

                // Read stdout/stderr in background
                process?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        Log.d(TAG, "[agent] $line")
                    }
                }

                val exitCode = process?.waitFor()
                Log.w(TAG, "Agent exited with code: $exitCode")

            } catch (e: Exception) {
                Log.e(TAG, "Agent crashed", e)
            }
        }
    }

    private fun extractBinary(): File? {
        // Android blocks exec() from files/ on API 29+ (W^X policy).
        // We extract to the app's nativeLibraryDir-adjacent location:
        // use a subfolder of filesDir but copy via shell chmod after extract.
        // The key is calling File.setExecutable() AND ensuring the parent dir
        // is not on a noexec mount — which filesDir can be on some devices.
        // Safest solution: extract to a file in the app's own OBB/data path
        // that we've confirmed is executable by running a test.

        // Primary target: filesDir (works on most devices)
        // Fallback: codeCacheDir (specifically designed for executable code)
        val candidates = listOf(
            File(filesDir, "zeroclaw"),
            File(codeCacheDir, "zeroclaw"),
        )

        val versionFile = File(filesDir, "zeroclaw.version")
        val currentVersion = getAppVersionCode().toString()
        val storedVersion = if (versionFile.exists()) versionFile.readText().trim() else ""

        // Find existing valid executable
        for (dest in candidates) {
            if (dest.exists() && dest.length() > 0 && storedVersion == currentVersion) {
                if (dest.canExecute()) return dest
                // Try to re-chmod
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
                if (dest.canExecute()) return dest
            }
        }

        // Extract fresh
        for (dest in candidates) {
            try {
                dest.parentFile?.mkdirs()
                applicationContext.assets.open("libzeroclaw.so").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // chmod via Runtime — more reliable than File.setExecutable on some ROMs
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()

                if (dest.canExecute()) {
                    versionFile.writeText(currentVersion)
                    Log.i(TAG, "Binary extracted to ${dest.absolutePath}")
                    return dest
                }
                Log.w(TAG, "Cannot execute from ${dest.absolutePath}, trying next location")
                dest.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Extraction to ${dest.absolutePath} failed: ${e.message}")
            }
        }

        Log.e(TAG, "All extraction targets failed — device may block exec from app dirs")
        return null
    }

    private fun getAppVersionCode(): Long {
        return try {
            val info: PackageInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    private fun generateDefaultConfig(): String {
        val dataPath = filesDir.absolutePath
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
root = "$dataPath/workspace"
allowed_paths = ["$dataPath"]

[mcp]
enabled = true

[security]
supervised = true
allow_shell = false
allow_filesystem = true

[memory]
backend = "sqlite"
path = "$dataPath/.zeroclaw/memory.db"
        """.trimIndent()
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
        process?.waitFor()
    }

    override fun onBind(intent: Intent?) = null
}
