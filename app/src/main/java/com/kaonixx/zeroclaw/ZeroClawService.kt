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
 * Embeds a lightweight HTTP gateway on 127.0.0.1:18789 so the app UI
 * always connects immediately. No native binary execution needed.
 */
class ZeroClawService : Service() {

    private var gatewayServer: GatewayServer? = null
    private var notificationScheduler: ScheduledExecutorService? = null
    private val startTime = AtomicLong(System.currentTimeMillis())
    private var paired = false
    private var authToken: String? = null

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

        startGatewayServer()
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
            .setContentText("Agent running on :18789")
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

    private fun startGatewayServer() {
        try {
            gatewayServer = GatewayServer(18789, startTime, filesDir,
                { paired }, { authToken }, { token -> authToken = token; paired = true })
            gatewayServer?.start()
            Log.i(TAG, "Gateway started on :18789")
        } catch (e: Exception) {
            Log.e(TAG, "Gateway failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationScheduler?.shutdown()
        gatewayServer?.stop()
    }

    override fun onBind(intent: Intent?) = null
}

// ======================================================================
// HTTP GATEWAY
// ======================================================================

class GatewayServer(
    private val port: Int,
    private val startTime: AtomicLong,
    private val filesDir: File,
    private val isPaired: () -> Boolean,
    private val getToken: () -> String?,
    private val setPaired: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val pool = Executors.newFixedThreadPool(4)
    private val thread = Thread(this::run, "Gateway")

    fun start() { running = true; serverSocket = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1")); thread.start() }
    fun stop() { running = false; try { serverSocket?.close() } catch (_: Exception) {}; pool.shutdown() }

    private fun run() {
        while (running) {
            try { pool.execute { handle(it) } } catch (_: Exception) { if (!running) break }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { sock ->
                val reader = sock.getInputStream().bufferedReader()
                val writer = sock.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.split("?").first()

                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isBlank()) break
                    if (header.lowercase().startsWith("content-length:"))
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val uptime = (System.currentTimeMillis() - startTime.get()) / 1000

                val response = when {
                    path == "/api/status" || path == "/api/health" -> json(
                        "version" to "1.0.0",
                        "gateway_port" to port,
                        "uptime_seconds" to uptime,
                        "paired" to isPaired(),
                        "daemon_started_at" to java.time.Instant.now().toString(),
                        "health" to mapOf("pid" to android.os.Process.myPid(), "uptime_seconds" to uptime, "components" to mapOf("gateway" to mapOf("status" to "ok"))),
                        "process" to mapOf("rss_bytes" to 0, "system_ram_total_bytes" to 0),
                        "channels" to emptyMap<String, Boolean>()
                    )
                    path == "/api/quickstart/state" -> json("quickstart_completed" to isPaired(), "agents" to emptyList<Any>())
                    path == "/api/quickstart/fields" -> """[]"""
                    path == "/api/agents" -> """[]"""
                    path.startsWith("/api/agent/") && method == "POST" && path.endsWith("/chat") -> json("id" to "", "role" to "assistant", "content" to "Embedded gateway ready. Pair with a remote agent to process messages.", "timestamp" to "now")
                    path.startsWith("/api/agent/") -> {
                        val alias = path.removePrefix("/api/agent/").removeSuffix("/").split("/").first()
                        json("alias" to alias, "name" to alias, "status" to "idle")
                    }
                    path == "/api/tools" -> """[]"""
                    path == "/api/config" -> """[{"name":"gateway","label":"Gateway","sections":[{"name":"gateway","keys":[{"key":"port","value":18789,"type":"integer"}]}]}]"""
                    path.startsWith("/api/config/") -> json("name" to "", "keys" to emptyList<Any>())
                    path == "/api/logs" -> """[]"""
                    path == "/api/cron" -> """[]"""
                    path == "/api/integrations" -> """[{"platform":"embedded","enabled":true,"status":"active","label":"Embedded Gateway"}]"""
                    path == "/api/doctor" -> """[{"id":"gateway","title":"Gateway Status","status":"pass","message":"Embedded gateway running"}]"""
                    path == "/api/admin/reload" -> json("success" to true)
                    path == "/api/admin/pair/code" -> json("pairing_code" to "EMBEDDED-GATEWAY")
                    path == "/api/admin/pair" && method == "POST" -> {
                        try {
                            val code = "\"code\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)?.groupValues?.getOrNull(1) ?: ""
                            if (code.isNotBlank()) {
                                setPaired(code)
                                json("token" to "embedded-token", "success" to true)
                            } else json("success" to false, "error" to "No code")
                        } catch (_: Exception) { json("success" to false) }
                    }
                    path == "/api/quickstart/apply" && method == "POST" -> json("success" to true, "message" to "Applied")
                    path == "/api/quickstart/dismiss" -> json("success" to true, "message" to "Dismissed")
                    else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                }

                val responseStr = if (response.startsWith("HTTP/1.1")) response
                else "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n$response"

                writer.write(responseStr.toByteArray())
                writer.flush()
            }
        } catch (_: Exception) {}
    }

    private fun json(vararg pairs: Pair<String, Any?>): String {
        val entries = pairs.map { (k, v) ->
            val value = when (v) {
                null -> "null"
                is String -> "\"${v.replace("\"", "\\\"")}\""
                is Boolean -> v.toString()
                is Number -> v.toString()
                is Map<*, *> -> {
                    val inner = v.entries.joinToString(",") { (ik, iv) ->
                        val ivs = when (iv) {
                            null -> "null"
                            is String -> "\"$iv\""
                            is Boolean -> iv.toString()
                            is Number -> iv.toString()
                            else -> "\"$iv\""
                        }
                        "\"$ik\":$ivs"
                    }
                    "{$inner}"
                }
                is List<*> -> "[${v.joinToString(",") { e -> when(e) { is String -> "\"$e\""; null -> "null"; else -> e.toString() } }}]"
                else -> "\"$v\""
            }
            "\"$k\":$value"
        }
        return "{${entries.joinToString(",")}}"
    }
}
