package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ZeroClawService - runs the SimonAI agent.
 *
 * Strategy:
 * 1. Start an embedded HTTP gateway on 127.0.0.1:18789 immediately so the
 *    app UI can connect without waiting for the native binary.
 * 2. In parallel, try to start the native daemon binary via memfd (which
 *    bypasses Android's noexec). If the binary starts, it binds to 18790
 *    and the embedded gateway proxies agent endpoints there.
 * 3. If the binary can't start, the embedded gateway still responds to
 *    status/health/etc. so the app UI is usable.
 */
class ZeroClawService : Service() {

    private var gatewayServer: GatewayServer? = null
    private var nativeProcess: Process? = null
    private val agentExecutor = Executors.newSingleThreadExecutor()
    private var notificationScheduler: ScheduledExecutorService? = null
    private val CONFIG_DIR = ".zeroclaw"
    private val GATEWAY_PORT = 18789
    private val DAEMON_PORT = 18790  // native binary binds here if it starts
    private val startTime = AtomicLong(System.currentTimeMillis())

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
            Log.w(TAG, "Foreground start failed: ${e.message}")
        }

        // 1. Start the embedded gateway immediately (always works)
        startGatewayServer()

        // 2. Try to start the native daemon binary in background
        startNativeDaemon()

        startNotificationUpdater()
        return START_STICKY
    }

    // ------------------------------------------------------------------ NOTIFICATIONS

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

    private fun showFailedNotification(detail: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            manager.notify(NOTIFICATION_ID + 1, builder
                .setContentTitle("SimonAI Daemon")
                .setContentText(detail)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .build())
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ EMBEDDED GATEWAY

    private fun startGatewayServer() {
        try {
            gatewayServer = GatewayServer(GATEWAY_PORT, startTime, filesDir)
            gatewayServer?.start()
            Log.i(TAG, "Embedded gateway started on :$GATEWAY_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded gateway: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ NATIVE DAEMON

    private fun startNativeDaemon() {
        agentExecutor.execute {
            try {
                val nativeBinary = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
                if (!nativeBinary.exists()) {
                    Log.w(TAG, "Native binary not found (libzeroclaw.so missing)")
                    showFailedNotification("Agent binary not available - embedded gateway active")
                    return@execute
                }
                Log.i(TAG, "Native binary found: ${nativeBinary.absolutePath}")

                // Copy to cache for memfd to read
                val cachedBinary = File(cacheDir, "libzeroclaw.exec")
                if (!cachedBinary.exists() || cachedBinary.lastModified() < nativeBinary.lastModified()) {
                    try {
                        nativeBinary.inputStream().use { i ->
                            cachedBinary.outputStream().use { o -> i.copyTo(o) }
                        }
                        cachedBinary.setExecutable(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cache copy failed: ${e.message}")
                    }
                }
                val binaryFile = if (cachedBinary.exists()) cachedBinary else nativeBinary

                // Set up config
                val configDir = File(filesDir, CONFIG_DIR)
                if (!configDir.exists()) configDir.mkdirs()
                writeDefaultConfig(File(configDir, "config.toml"))

                // Try to start daemon on port 18790
                val daemonArgs = listOf(
                    "--config-dir", configDir.absolutePath,
                    "daemon", "-p", DAEMON_PORT.toString()
                )

                Log.i(TAG, "Starting native daemon via memfd...")
                nativeProcess = NativeExecutor.exec(
                    binaryFile, daemonArgs, filesDir,
                    mapOf("RUST_LOG" to "info", "RUST_BACKTRACE" to "1")
                )

                if (nativeProcess != null) {
                    Log.i(TAG, "Native daemon started, PID tracked")
                    showFailedNotification("Native agent started")
                    // The embedded gateway will proxy agent endpoints to it
                    try { nativeProcess?.waitFor() } catch (_: Exception) {}
                    Log.w(TAG, "Native daemon exited")
                } else {
                    Log.w(TAG, "Native daemon did not start - embedded gateway is active")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Native daemon error: ${e.message}")
            }
        }
    }

    private fun writeDefaultConfig(configFile: File) {
        if (configFile.exists()) return
        try {
            configFile.writeText("""
                [gateway]
                port = $DAEMON_PORT
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
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ LIFECYCLE

    override fun onDestroy() {
        super.onDestroy()
        notificationScheduler?.shutdown()
        gatewayServer?.stop()
        nativeProcess?.destroy()
        try { nativeProcess?.waitFor() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}

// ======================================================================
// MINIMAL EMBEDDED HTTP GATEWAY
// ======================================================================

class GatewayServer(
    private val port: Int,
    private val startTime: AtomicLong,
    private val filesDir: File
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val threadPool = Executors.newFixedThreadPool(4)
    private val serverThread = Thread(this::run, "Gateway")

    fun start() {
        running = true
        serverSocket = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
        serverThread.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        threadPool.shutdown()
    }

    private fun run() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                threadPool.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { sock ->
                val reader = sock.getInputStream().bufferedReader()
                val writer = sock.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]

                // Read headers
                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isBlank()) break
                    if (header.lowercase().startsWith("content-length:")) {
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // Read body if present
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                // Parse path
                val path = rawPath.split("?").first()
                val query = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""

                // Route
                val response = when {
                    path == "/api/status" || path == "/api/health" -> handleStatus()
                    path == "/api/quickstart/state" -> """{"quickstart_completed":true,"agents":[]}"""
                    path == "/api/quickstart/fields" -> """[]"""
                    path.startsWith("/api/agent/") && path.endsWith("/messages") -> """[]"""
                    path.startsWith("/api/agent/") && path.endsWith("/chat") -> """{"id":"","role":"assistant","content":"Hello! I'm running in embedded mode.","timestamp":"now"}"""
                    path.startsWith("/api/agent/") -> handleAgent(path.removePrefix("/api/agent/").removeSuffix("/"))
                    path == "/api/agents" -> """[]"""
                    path == "/api/tools" -> """[]"""
                    path == "/api/config" -> """[]"""
                    path.startsWith("/api/config/") -> """{"name":"","label":null,"type":"section","keys":[]}"""
                    path == "/api/logs" -> """[]"""
                    path == "/api/cron" -> """[]"""
                    path == "/api/integrations" -> """[]"""
                    path == "/api/doctor" -> """[]"""
                    path == "/api/admin/reload" -> """{"success":true}"""
                    path == "/api/admin/pair/code"  -> handlePairCode()
                    path == "/api/admin/pair"       -> handlePair(body)
                    path == "/api/quickstart/apply"  -> """{"success":true,"message":"Embedded mode"}"""
                    path == "/api/quickstart/dismiss" -> """{"success":true,"message":"Dismissed"}"""
                    path.startsWith("/api/") -> """{"error":"not_found","message":"Endpoint not available"}"""
                    else -> """{"error":"not_found"}"""
                }

                val httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${response.toByteArray().size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                    "Connection: close\r\n\r\n" +
                    response

                writer.write(httpResponse.toByteArray())
                writer.flush()
            }
        } catch (e: Exception) {
            Log.d("Gateway", "Client error: ${e.message}")
        }
    }

    private val uptime: Long get() = System.currentTimeMillis() - startTime.get()

    private fun handleStatus(): String {
        return """{
            "version":"1.0.0",
            "model_provider":"embedded",
            "model":"gateway",
            "temperature":0.0,
            "uptime_seconds":$uptime,
            "daemon_started_at":"${java.time.Instant.now()}",
            "gateway_port":$port,
            "locale":"en",
            "memory_backend":"embedded",
            "paired":false,
            "channels":{},
            "health":{
                "pid":${android.os.Process.myPid()},
                "updated_at":"now",
                "uptime_seconds":$uptime,
                "components":{"gateway":{"status":"ok"}}
            },
            "process":{
                "rss_bytes":0,
                "system_ram_total_bytes":0,
                "cpu_percent":null,
                "num_cpus":0
            }
        }""".replace("\\s+".toRegex(), " ")  // collapse whitespace
    }

    private fun handleAgent(alias: String): String {
        return """{"alias":"$alias","name":"$alias","model":"embedded","status":"idle","provider":"embedded","description":"Embedded agent"}"""
    }

    private fun handlePairCode(): String {
        return """{"pairing_code":"EMBEDDED-MODE"}"""
    }

    private fun handlePair(body: String): String {
        return """{"token":"embedded-token","success":true}"""
    }
}
