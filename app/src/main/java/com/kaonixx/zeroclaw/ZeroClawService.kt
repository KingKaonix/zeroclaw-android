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

    /** Try execve() path first, fall back to linker if the mount is noexec.
      *
      * On Android 10+, app data dirs are typically mounted noexec. This means
      * both direct execve() and the linker's mmap(PROT_EXEC) fail from cacheDir
      * or filesDir. The system's nativeLibraryDir is sometimes executable;
      * try it first. If direct execution fails with EACCES (permission denied),
      * fall back to running via the system linker:
      *
      *   /system/bin/linker64 /path/to/binary <args>
      *
      * The linker itself is a system binary (executable), and on some Android
      * versions it can mmap(PROT_EXEC) from app dirs where execve() fails.
      */
    private fun buildCommand(binary: File, args: List<String>): List<String> {
        return listOf(binary.absolutePath) + args
    }

    private fun buildLinkerCommand(binary: File, args: List<String>): List<String> {
        val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64"
                     else "/system/bin/linker"
        return listOf(linker, binary.absolutePath) + args
    }

    /** Try running a subprocess with fallback: direct exec -> linker -> nativeLibraryDir direct -> nativeLibraryDir linker. */
    private fun launchProcess(
        nativeBinary: File,
        cachedBinary: File,
        args: List<String>,
        workDir: File,
        env: Map<String, String> = emptyMap()
    ): java.lang.Process? {
        data class Strategy(val label: String, val bin: File, val useLinker: Boolean)

        val strategies = listOf(
            Strategy("cached+exec",   cachedBinary,  false),
            Strategy("cached+linker", cachedBinary,  true),
            Strategy("native+exec",   nativeBinary,  false),
            Strategy("native+linker", nativeBinary,  true),
        )

        var lastError: Exception? = null
        for (s in strategies) {
            try {
                val cmd = if (s.useLinker) buildLinkerCommand(s.bin, args) else buildCommand(s.bin, args)
                Log.i(TAG, "Attempt [${s.label}]: ${cmd.joinToString(" ").take(200)}")
                val pb = ProcessBuilder(cmd)
                pb.directory(workDir)
                pb.redirectErrorStream(true)
                env.forEach { (k, v) -> pb.environment()[k] = v }
                pb.environment()["HOME"] = workDir.absolutePath
                pb.environment()["RUST_LOG"] = "info"
                val p = pb.start()
                Thread.sleep(500)
                if (p.isAlive) {
                    Log.i(TAG, "Process started successfully via [${s.label}]")
                    return p
                }
                val output = p.inputStream.bufferedReader().readText().trim()
                val exitCode = p.exitValue()
                Log.w(TAG, "Process [${s.label}] exited quickly with code $exitCode: ${output.take(200)}")
                lastError = IOException("Exit code $exitCode: ${output.take(100)}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Strategy [${s.label}] failed: ${e.message}")
                lastError = e
            } catch (e: IOException) {
                Log.w(TAG, "Strategy [${s.label}] failed: ${e.message}")
                lastError = e
            }
        }
        Log.e(TAG, "All execution strategies failed", lastError)
        return null
    }

    private fun startAgent() {
        agentExecutor.execute {
            try {
                val nativeBinary = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
                if (!nativeBinary.exists()) {
                    Log.e(TAG, "Native library not found at ${nativeBinary.absolutePath}")
                    return@execute
                }
                Log.i(TAG, "Found native library at ${nativeBinary.absolutePath}")

                // Prepare cached copy (for strategies that need a local writable copy)
                val cachedBinary = File(cacheDir, "libzeroclaw.exec")
                if (!cachedBinary.exists() || cachedBinary.lastModified() < nativeBinary.lastModified()) {
                    try {
                        nativeBinary.copyTo(cachedBinary, overwrite = true)
                        cachedBinary.setExecutable(true)
                        Log.i(TAG, "Cached binary at ${cachedBinary.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to create cached copy, will use nativeLibraryDir directly")
                    }
                }

                val configDir = File(filesDir, CONFIG_DIR)
                if (!configDir.exists()) configDir.mkdirs()

                // Generate config if first launch
                val configFile = File(configDir, "config.toml")
                if (!configFile.exists()) {
                    Log.i(TAG, "Generating canonical config...")
                    val genResult = runBinary(nativeBinary, cachedBinary, listOf(
                        "--config-dir", configDir.absolutePath,
                        "config", "generate"
                    ))
                    Log.i(TAG, "Config generate: ${genResult?.take(200) ?: "failed"}")

                    Log.i(TAG, "Applying Android config overrides...")
                    listOf(
                        listOf("gateway", "port", GATEWAY_PORT.toString()),
                        listOf("gateway", "web_dist_dir", "${filesDir.absolutePath}/web/dist"),
                        listOf("agents.default", "enabled", "true"),
                        listOf("mcp", "enabled", "true"),
                        listOf("memory", "backend", "sqlite"),
                    ).forEach { (section, key, value) ->
                        runBinary(nativeBinary, cachedBinary, listOf(
                            "--config-dir", configDir.absolutePath,
                            "config", "set", "$section.$key", value
                        ))
                    }
                    Log.i(TAG, "Config setup complete")
                }

                val daemonArgs = listOf(
                    "--config-dir", configDir.absolutePath,
                    "daemon", "-p", GATEWAY_PORT.toString()
                )

                process = launchProcess(
                    nativeBinary, cachedBinary, daemonArgs, filesDir,
                    mapOf("RUST_LOG" to "info")
                )

                if (process == null) {
                    Log.e(TAG, "Failed to start daemon - all strategies exhausted")
                    showFailedNotification()
                    return@execute
                }

                Log.i(TAG, "Agent started.")
                Log.i(TAG, "Gateway: http://127.0.0.1:$GATEWAY_PORT")

                // Read stdout/stderr and log it
                process?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        Log.d(TAG, "[agent] $line")
                    }
                }

                val exitCode = process?.waitFor()
                Log.w(TAG, "Agent exited with code: $exitCode")

            } catch (e: Exception) {
                Log.e(TAG, "Agent crashed", e)
                showFailedNotification()
            }
        }
    }

    private fun showFailedNotification() {
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
                .setContentText("Agent daemon could not start. Check logs.")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .build())
        } catch (_: Exception) {}
    }

    /** Run a one-shot binary command via fallback strategies, capture stdout. */
    private fun runBinary(
        nativeBinary: File,
        cachedBinary: File,
        args: List<String>
    ): String? {
        data class BinStrategy(val label: String, val bin: File, val useLinker: Boolean)

        val strategies = listOf(
            BinStrategy("cached+exec",   cachedBinary,  false),
            BinStrategy("cached+linker", cachedBinary,  true),
            BinStrategy("native+exec",   nativeBinary,  false),
            BinStrategy("native+linker", nativeBinary,  true),
        )

        for (s in strategies) {
            try {
                val cmd = if (s.useLinker) buildLinkerCommand(s.bin, args) else buildCommand(s.bin, args)
                val pb = ProcessBuilder(cmd)
                pb.directory(filesDir)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val output = p.inputStream.bufferedReader().readText().trim()
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "Binary cmd [$label] succeeded: ${output.take(100)}")
                    return output
                }
                Log.w(TAG, "Binary cmd [$label] exited $exitCode: ${output.take(100)}")
            } catch (e: Exception) {
                Log.d(TAG, "Binary cmd [$label] failed: ${e.message}")
            }
        }
        Log.w(TAG, "Binary cmd '${args.firstOrNull()}' failed in all strategies")
        return null
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
