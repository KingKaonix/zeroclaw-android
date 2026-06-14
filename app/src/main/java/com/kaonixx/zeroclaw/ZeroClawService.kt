package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.net.HttpURLConnection
import java.net.URL

/** Manages the native zeroclaw daemon as a foreground service. */
class ZeroClawService : Service() {

    private var daemonProcess: Process? = null
    private val executor = Executors.newSingleThreadExecutor()
    internal var pairingCode: String? = null
        private set
    internal var authToken: String? = null
        private set

    companion object {
        const val TAG = "SimonAI"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "simonai_service"
        const val ACTION_STOP = "com.kaonixx.zeroclaw.STOP_SERVICE"
        const val DAEMON_PORT = 18789
        val pairingCodeRegex = Regex("""│\s*(\d{6})\s*│""")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDaemon()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.w(TAG, "Foreground failed (${e.message}), running as regular service")
        }

        if (daemonProcess == null) {
            executor.execute { startDaemon() }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(
                CHANNEL_ID,
                "SimonAI Agent",
                NotificationManager.IMPORTANCE_LOW
            )
            c.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(c)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ZeroClawService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("SimonAI")
            .setContentText("Daemon active on :$DAEMON_PORT")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Find the zeroclaw binary, prepare the config, and start the daemon.
     */
    private fun startDaemon() {
        try {
            val configDir = File(filesDir, ".zeroclaw")
            configDir.mkdirs()
            val configFile = File(configDir, "config.toml")

            val binaryFile = findBinary() ?: run {
                Log.e(TAG, "No binary found, daemon won't start")
                return
            }
            Log.i(TAG, "Using binary: " + binaryFile.absolutePath + " (" + binaryFile.length() + " bytes)")

            if (!binaryFile.canExecute()) {
                binaryFile.setExecutable(true)
                Log.i(TAG, "Set executable permission on binary")
            }

            val configNeedsSetup = !configFile.exists() ||
                (configFile.exists() && !configFile.readText().contains("require_pairing = false"))

            if (configNeedsSetup) {
                firstLaunchSetup(binaryFile, configDir, configFile)
                return
            }

            startDaemonProcess(binaryFile, configDir)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native daemon: " + e.message)
            daemonProcess = null
        }
    }

    private fun findBinary(): File? {
        // 1. Try native lib dir (extracted from jniLibs by package manager)
        try {
            val nativeLibFile = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
            if (nativeLibFile.exists()) {
                Log.i(TAG, "Found binary in nativeLibDir: " + nativeLibFile.absolutePath)
                return nativeLibFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "nativeLibraryDir not accessible: " + e.message)
        }

        // 2. Try filesDir copy
        val filesBinary = File(filesDir, "zeroclaw")
        if (filesBinary.exists()) {
            Log.i(TAG, "Found binary in filesDir")
            return filesBinary
        }

        // 3. Try extracting from nativeLibDir to filesDir
        try {
            val nativeLibFile = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
            if (nativeLibFile.exists()) {
                Log.i(TAG, "Copying binary from nativeLibDir to filesDir")
                copyFile(nativeLibFile, filesBinary)
                filesBinary.setExecutable(true)
                if (filesBinary.canExecute()) {
                    Log.i(TAG, "Binary copied and made executable")
                    return filesBinary
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy from nativeLibDir: " + e.message)
        }

        // 4. Try extracting from assets
        try {
            Log.i(TAG, "Trying to extract binary from assets")
            val ais = assets.open("zeroclaw")
            copyStream(ais, FileOutputStream(filesBinary))
            ais.close()
            filesBinary.setExecutable(true)
            if (filesBinary.canExecute()) {
                Log.i(TAG, "Binary extracted from assets")
                return filesBinary
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract from assets: " + e.message)
        }

        Log.e(TAG, "Could not find or extract the zeroclaw binary")
        return null
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        input.use { i ->
            output.use { o ->
                i.copyTo(o)
            }
        }
    }

    /**
     * First launch: generate daemon config, modify it, then start for real.
     */
    private fun firstLaunchSetup(
        binaryFile: File,
        configDir: File,
        configFile: File
    ) {
        executor.execute {
            try {
                Log.i(TAG, "First launch: generating config...")
                val pairCodeRef = AtomicReference<String?>(null)
                val proc = startProcess(binaryFile, configDir, pairCodeRef)
                if (proc == null) {
                    Log.e(TAG, "Failed to start daemon for config generation")
                    return@execute
                }

                Thread.sleep(3000)
                proc.destroyForcibly()

                if (configFile.exists()) {
                    Log.i(TAG, "Config generated, modifying to disable pairing")
                    var configText = configFile.readText()
                    configText = configText.replace(
                        "require_pairing = true",
                        "require_pairing = false"
                    )
                    if (!configText.contains("require_pairing = false")) {
                        configText = configText.replace(
                            "[gateway]",
                            "[gateway]\nrequire_pairing = false"
                        )
                    }
                    configFile.writeText(configText)
                    Log.i(TAG, "Config modified: require_pairing=false")
                } else {
                    Log.w(TAG, "Config not generated, creating minimal config")
                    createMinimalConfig(configFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "First launch setup failed: " + e.message)
            }

            startDaemonProcess(binaryFile, configDir)
        }
    }

    private fun startDaemonProcess(binaryFile: File, configDir: File) {
        try {
            val pairCodeRef = AtomicReference<String?>(null)
            val proc = startProcess(binaryFile, configDir, pairCodeRef)
            if (proc == null) {
                Log.e(TAG, "Failed to start daemon")
                return
            }
            daemonProcess = proc

            // Try auto-pair
            Thread.sleep(2000)
            val savedCode = try { File(filesDir, ".pairing_code").readText() } catch (_: Exception) { null }
            val code = savedCode ?: pairCodeRef.get()
            if (code != null) {
                try { autoPair(code) } catch (e: Exception) {
                    Log.w(TAG, "Auto-pair failed: " + e.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemon: " + e.message)
        }
    }

    private fun startProcess(
        binaryFile: File,
        configDir: File,
        pairCodeRef: AtomicReference<String?>
    ): Process? {
        return try {
            val cmd = listOf(
                binaryFile.absolutePath,
                "--config-dir", configDir.absolutePath,
                "daemon",
                "-p", DAEMON_PORT.toString()
            )
            val env = mapOf(
                "RUST_LOG" to "info",
                "RUST_BACKTRACE" to "1",
                "HOME" to filesDir.absolutePath
            )

            Log.i(TAG, "Starting: " + cmd.joinToString(" "))
            val pb = ProcessBuilder(cmd)
                .directory(filesDir)
                .redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }

            val proc = pb.start()
            Log.i(TAG, "Daemon started")

            Thread {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            Log.i(TAG, "[daemon] $line")
                            val m = pairingCodeRegex.find(line)
                            if (m != null) pairCodeRef.set(m.groupValues[1])
                        }
                    }
                } catch (_: IOException) { }
            }.apply { isDaemon = true }.start()

            proc
        } catch (e: Exception) {
            Log.e(TAG, "startProcess failed: " + e.message)
            null
        }
    }

    private fun autoPair(code: String) {
        val url = URL("http://127.0.0.1:" + DAEMON_PORT + "/pair")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-Pairing-Code", code)
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val tokenMatch = Regex(""""token"\s*:\s*"([^"]+)"""").find(body)
                if (tokenMatch != null) {
                    authToken = tokenMatch.groupValues[1]
                    File(filesDir, ".auth_token").writeText(authToken!!)
                    Log.i(TAG, "Auto-pair successful")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun createMinimalConfig(configFile: File) {
        val cfg = """
default_provider = "openrouter"
default_model = "gpt-4o"
default_temperature = 0.7

[observability]
backend = "none"

[agent]
compact_context = false
max_tool_iterations = 10
max_history_messages = 50
parallel_tools = false

[memory]
backend = "sqlite"
auto_save = true

[gateway]
port = """ + DAEMON_PORT + """
host = "127.0.0.1"
require_pairing = false
""".trimIndent()
        configFile.writeText(cfg)
        Log.i(TAG, "Created minimal config")
    }

    private fun stopDaemon() {
        daemonProcess?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
                Log.i(TAG, "Daemon killed")
            }
        }
        daemonProcess = null
    }

    override fun onDestroy() {
        stopDaemon()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
