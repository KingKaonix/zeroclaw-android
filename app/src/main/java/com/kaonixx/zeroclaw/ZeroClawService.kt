package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
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
        const val ACTION_STOP = "com.kaonixx.zeroclaw.STOP_SERVICE"
    }

    /** System linker for running standalone PIE binaries on Android.
      * Android 10+ mounts app data dirs noexec, so direct ProcessBuilder calls
      * fail with EACCES even after setExecutable(true). The linker is a system
      * binary that is always executable and can load our ELF from a read-only
      * file. */
    private val LINKER: String by lazy {
        if (File("/system/bin/linker64").exists()) "/system/bin/linker64"
        else "/system/bin/linker"
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
                val binary = extractBinary()
                if (binary == null) {
                    Log.e(TAG, "Binary extraction failed")
                    return@execute
                }

                val configDir = File(filesDir, CONFIG_DIR)
                if (!configDir.exists()) configDir.mkdirs()

                // Create canonical config using daemon's own tools
                val configFile = File(configDir, "config.toml")
                if (!configFile.exists()) {
                    Log.i(TAG, "Generating canonical config...")
                    val genResult = runBinary(binary, listOf(
                        "--config-dir", configDir.absolutePath,
                        "config", "generate"
                    ), filesDir)
                    Log.i(TAG, "Config generate: ${genResult.trim().take(100)}")

                    // Apply Android-specific overrides.
                    // Model provider / agent model config is done through the
                    // web UI quickstart at http://127.0.0.1:18789/quickstart
                    Log.i(TAG, "Applying Android config overrides...")
                    listOf(
                        listOf("gateway", "port", GATEWAY_PORT.toString()),
                        listOf("gateway", "web_dist_dir", "${filesDir.absolutePath}/web/dist"),
                        listOf("agents.default", "enabled", "true"),
                        listOf("mcp", "enabled", "true"),
                        listOf("memory", "backend", "sqlite"),
                    ).forEach { (section, key, value) ->
                        runBinary(binary, listOf(
                            "--config-dir", configDir.absolutePath,
                            "config", "set", "$section.$key", value
                        ), filesDir)
                    }
                    Log.i(TAG, "Config setup complete")
                }

                // Use the system linker to load the binary — Android 10+ mounts
                // app data dirs noexec, making direct exec() impossible.
                val pb = ProcessBuilder(
                    LINKER,
                    binary.absolutePath,
                    "--config-dir", configDir.absolutePath,
                    "daemon",
                    "-p", GATEWAY_PORT.toString()
                )
                pb.directory(filesDir)
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
        val nativeLib = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
        if (!nativeLib.exists()) {
            Log.e(TAG, "Native library not found at ${nativeLib.absolutePath}")
            return null
        }
        Log.i(TAG, "Found native library at ${nativeLib.absolutePath}")

        // Copy to a cached location so the linker can read it reliably across
        // Android versions (nativeLibraryDir symlink behaviour varies).
        val cached = File(cacheDir, "libzeroclaw.exec")
        if (!cached.exists() || cached.lastModified() < nativeLib.lastModified()) {
            try {
                nativeLib.copyTo(cached, overwrite = true)
                Log.i(TAG, "Prepared executable at ${cached.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare cached executable", e)
                return nativeLib
            }
        }
        // NOTE: We never call setExecutable() — Android 10+ mounts app data
        // dirs noexec, which silently ignores the exec bit. Instead, all
        // ProcessBuilders use the system linker (LINKER field above) to load
        // this binary. The linker only needs read permission on the file.
        return cached
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
    private fun runBinary(binary: File, args: List<String>, workDir: File): String {
        return try {
            // Use linker — same reason as startAgent (noexec on Android 10+)
            val pb = ProcessBuilder(LINKER, binary.absolutePath, *args.toTypedArray())
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().readText().trim().also {
                p.waitFor()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Binary command ${args.firstOrNull()} failed: ${e.message}")
            ""
        }
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
