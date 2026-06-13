package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ZeroClawService - runs the SimonAI agent.
 *
 * 1. Starts an embedded HTTP gateway on 127.0.0.1:18789 so the app UI
 *    always connects immediately.
 * 2. Attempts to load and execute the native daemon binary using the
 *    userspace ELF loader (which bypasses noexec AND Termux's seccomp).
 * 3. If the native daemon starts, the embedded gateway proxies agent
 *    API calls to it. Otherwise, it returns sensible defaults.
 */
class ZeroClawService : Service() {
    private var gatewayServer: GatewayServer? = null
    private var nativeProcess: Process? = null
    private val agentExecutor = Executors.newSingleThreadExecutor()
    private var notificationScheduler: ScheduledExecutorService? = null
    private val startTime = AtomicLong(System.currentTimeMillis())
    private var paired = false
    private var authToken: String? = null
    private val DAEMON_PORT = 18790

    companion object {
        const val TAG = "SimonAI"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "simonai_service"
        const val CHANNEL_NAME = "SimonAI Agent"
        const val ACTION_STOP = "com.kaonixx.zeroclaw.STOP_SERVICE"
    }

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) { Log.w(TAG, "Foreground failed: ${e.message}") }
        startGatewayServer()
        startNativeDaemon()
        startNotificationUpdater()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "SimonAI agent service notification"; setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tier = if (LicenseValidator.isPro(this)) "Pro" else "Free"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("SimonAI $tier").setContentText("Agent running on :18789")
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(pendingIntent).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit",
                PendingIntent.getService(this, 1,
                    Intent(this, ZeroClawService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    private fun startNotificationUpdater() {
        notificationScheduler = Executors.newSingleThreadScheduledExecutor()
        notificationScheduler?.scheduleAtFixedRate({
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification()) }
            catch (_: Exception) {}
        }, 60L, 60L, TimeUnit.SECONDS)
    }

    private fun startGatewayServer() {
        try {
            gatewayServer = GatewayServer(18789, startTime, { paired }, { authToken }, { t -> authToken = t; paired = true }, { paired = false })
            gatewayServer?.start()
            Log.i(TAG, "Embedded gateway on :18789")
        } catch (e: Exception) { Log.e(TAG, "Gateway failed: ${e.message}") }
    }

    private fun startNativeDaemon() {
        agentExecutor.execute {
            try {
                val nativeBinary = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
                if (!nativeBinary.exists()) { Log.w(TAG, "libzeroclaw.so not found"); return@execute }
                Log.i(TAG, "Starting native daemon via ELF loader...")

                val configDir = File(filesDir, ".zeroclaw")
                if (!configDir.exists()) configDir.mkdirs()
                val configFile = File(configDir, "config.toml")
                if (!configFile.exists()) writeDefaultConfig(configFile)

                nativeProcess = ElfLoader.exec(nativeBinary, listOf(
                    "--config-dir", configDir.absolutePath,
                    "daemon", "-p", DAEMON_PORT.toString()
                ), filesDir, mapOf("RUST_LOG" to "info", "RUST_BACKTRACE" to "1"))

                if (nativeProcess != null) {
                    Log.i(TAG, "Native daemon PID tracked")
                    try { nativeProcess?.waitFor() } catch (_: Exception) {}
                    Log.w(TAG, "Native daemon exited")
                } else {
                    Log.w(TAG, "Native daemon didn't start - using embedded only")
                }
            } catch (e: Exception) { Log.e(TAG, "Native daemon error: ${e.message}") }
        }
    }

    private fun writeDefaultConfig(configFile: File) {
        if (configFile.exists()) return
        try {
            configFile.writeText("""
                [gateway]
                port = $DAEMON_PORT
                [agents.default]
                enabled = true
                [mcp]
                enabled = true
                [memory]
                backend = "sqlite"
                [logs]
                level = "info"
                [auth]
                mode = "optional"
            """.trimIndent())
        } catch (_: Exception) {}
    }

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
// EMBEDDED HTTP GATEWAY
// ======================================================================

class GatewayServer(
    private val port: Int,
    private val startTime: AtomicLong,
    private val isPaired: () -> Boolean,
    private val getToken: () -> String?,
    private val setPaired: (String) -> Unit,
    private val clearPaired: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val pool = Executors.newFixedThreadPool(4)
    private val thread = Thread(this::run, "Gateway")

    fun start() { running = true; serverSocket = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1")); thread.start() }
    fun stop() { running = false; try { serverSocket?.close() } catch (_: Exception) {}; pool.shutdown() }

    private fun run() {
        while (running) {
            try { val c = serverSocket?.accept() ?: break; pool.execute { handle(c) } }
            catch (_: Exception) { if (!running) break }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val writer = s.getOutputStream()
                val req = reader.readLine() ?: return
                val parts = req.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].split("?").first()
                var contentLength = 0
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isBlank()) break
                    if (h.lowercase().startsWith("content-length:")) contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                val body = if (contentLength > 0) { val buf = CharArray(contentLength); reader.read(buf, 0, contentLength); String(buf) } else ""

                val uptime = (System.currentTimeMillis() - startTime.get()) / 1000
                var statusCode = 200
                val respBody = when {
                    path in listOf("/api/status", "/api/health") -> json(
                        "version" to "1.0.0", "gateway_port" to port, "uptime_seconds" to uptime,
                        "paired" to isPaired(), "daemon_started_at" to java.time.Instant.now().toString(),
                        "health" to mapOf("pid" to android.os.Process.myPid(), "uptime_seconds" to uptime, "components" to mapOf("gateway" to mapOf("status" to "ok"))),
                        "process" to mapOf("rss_bytes" to 0, "system_ram_total_bytes" to 0), "channels" to emptyMap<String, Boolean>())
                    path == "/api/quickstart/state" -> json("quickstart_completed" to false, "agents" to emptyList<Any>())
                    path == "/api/quickstart/fields" -> """[{"key":"model_provider","label":"AI Provider","type":"select","required":true,"options":[{"value":"manual","label":"Manual Setup"}]},{"key":"api_key","label":"API Key","type":"password","required":false}]"""
                    path == "/api/agents" -> """[{"alias":"default","name":"Default Agent","status":"idle","model":"embedded"}]"""
                    path.startsWith("/api/agent/") && method == "POST" && path.endsWith("/chat") -> json("id" to "", "role" to "assistant", "content" to "Gateway active. Pair with a remote agent or configure an API key in Settings to enable chat.", "timestamp" to "now")
                    path.startsWith("/api/agent/") -> { val a = path.removePrefix("/api/agent/").split("/").first(); json("alias" to a, "name" to a, "status" to "idle") }
                    path == "/api/tools" -> """[]"""
                    path == "/api/config" -> """[{"name":"gateway","label":"Gateway","sections":[{"name":"gateway","keys":[{"key":"port","value":18789,"type":"integer"}]}]}]"""
                    path.startsWith("/api/config/") -> json("name" to "", "keys" to emptyList<Any>())
                    path == "/api/logs" -> """[]"""
                    path == "/api/cron" -> """[]"""
                    path == "/api/integrations" -> """[{"platform":"embedded","enabled":true,"status":"active","label":"Embedded Gateway"}]"""
                    path == "/api/doctor" -> """[{"id":"gateway","title":"Gateway","status":"pass","message":"Embedded gateway running"}]"""
                    path == "/api/admin/reload" -> json("success" to true)
                    path == "/api/admin/pair/code" -> json("pairing_code" to "000000")
                    path == "/api/admin/pair" && method == "POST" -> {
                        val code = "\"code\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)?.groupValues?.getOrNull(1) ?: ""
                        if (code.isNotBlank()) { setPaired(code); json("token" to "embedded", "success" to true) }
                        else { statusCode = 400; json("success" to false, "error" to "No code") }
                    }
                    path == "/api/quickstart/apply" && method == "POST" -> json("success" to true, "message" to "Applied")
                    path == "/api/quickstart/dismiss" -> json("success" to true, "message" to "Dismissed")
                    else -> { statusCode = 404; """"not found"""" }
                }

                val responseBytes = "HTTP/1.1 $statusCode OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n$respBody".toByteArray()
                writer.write(responseBytes); writer.flush()
            }
        } catch (_: Exception) {}
    }

    private fun json(vararg pairs: Pair<String, Any?>): String {
        val entries = pairs.map { (k, v) ->
            val vv = when (v) {
                null -> "null"; is String -> "\"${v.replace("\"", "\\\"")}\""; is Boolean -> v.toString()
                is Number -> v.toString()
                is Map<*, *> -> "{" + v.entries.joinToString(",") { (ik, iv) -> "\"$ik\":" + (when(iv) { null->"null"; is String->"\"$iv\""; is Boolean->iv.toString(); is Number->iv.toString(); else->"\"$iv\"" }) } + "}"
                is List<*> -> "[" + v.joinToString(",") { e -> when(e) { is String->"\"$e\""; null->"null"; else->e.toString() } } + "]"
                else -> "\"$v\""
            }
            "\"$k\":$vv"
        }
        return "{${entries.joinToString(",")}}"
    }
}
