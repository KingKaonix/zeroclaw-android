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
        const val TAG = "SimonAI"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "simonai_service"
        const val CHANNEL_NAME = "SimonAI Agent"
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
                    "daemon",
                    "-p", GATEWAY_PORT.toString()
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
        // On Android 10+ W^X policy blocks exec() from any writable app directory.
        // The only directory Android allows executing native code from is the
        // app's nativeLibraryDir — which is populated automatically from .so
        // files in src/main/jniLibs/arm64-v8a/ at install time.
        // We ship the binary as libzeroclaw.so in jniLibs and exec it directly.
        val nativeLib = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
        if (nativeLib.exists()) {
            Log.i(TAG, "Using native library at ${nativeLib.absolutePath}")
            return nativeLib
        }
        Log.e(TAG, "Native library not found at ${nativeLib.absolutePath}")
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
# SimonAI Android - Default Config
# Edit this file to configure your agent.

[agent]
name = "SimonAI-Android"
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
