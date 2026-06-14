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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Pure embedded gateway with foreground service to prevent Android from killing it. */
class ZeroClawService : Service() {
    private var gateway: GatewayServer? = null
    private val startTime = AtomicLong(System.currentTimeMillis())

    companion object {
        const val TAG = "SimonAI"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "simonai_service"
        const val ACTION_STOP = "com.kaonixx.zeroclaw.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Try to become a foreground service so Android doesn't kill us
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Foreground service started")
        } catch (e: Exception) {
            // Permission not granted or Android 14+ restriction – run as regular service.
            // The gateway will still work while the app is in the foreground.
            Log.w(TAG, "Foreground failed (${e.message}), running as regular service")
        }

        try {
            if (gateway == null) {
                gateway = GatewayServer(18789, startTime)
                gateway?.start()
                Log.i(TAG, "Gateway on :18789")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway failed: ${e.message}")
        }
        return START_STICKY
    }

    private fun createChannel() {
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
            .setContentText("Gateway active on :18789")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        gateway?.stop()
    }

    override fun onBind(intent: Intent?) = null
}

class GatewayServer(private val port: Int, private val startTime: AtomicLong) {
    private var ss: ServerSocket? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        running = true
        ss = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (running) try {
                val c = ss?.accept() ?: break
                pool.execute { handle(c) }
            } catch (_: Exception) {
                if (!running) break
            }
        }.apply { name = "Gateway"; start() }
    }

    fun stop() {
        running = false
        try { ss?.close() } catch(_: Exception) {}
        pool.shutdown()
    }

    private fun handle(s: Socket) {
        try {
            s.use { sock ->
                val r = sock.getInputStream().bufferedReader()
                val w = sock.getOutputStream()
                val line = r.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1].split("?").first()
                var cl = 0
                while (true) {
                    val h = r.readLine() ?: break
                    if (h.isBlank()) break
                    if (h.lowercase().startsWith("content-length:"))
                        cl = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                val body = if (cl > 0) {
                    val buf = CharArray(cl)
                    r.read(buf, 0, cl)
                    String(buf)
                } else ""

                val uptime = (System.currentTimeMillis() - startTime.get()) / 1000

                val resp = when {
                    path == "/api/status" || path == "/api/health" -> """{
                        "version":"1.0.0","gateway_port":18789,"uptime_seconds":$uptime,"paired":false,
                        "daemon_started_at":"${java.time.Instant.now()}",
                        "health":{"pid":${android.os.Process.myPid()},"uptime_seconds":$uptime,"components":{"gateway":{"status":"ok"}}},
                        "process":{"rss_bytes":0,"system_ram_total_bytes":0},"channels":{}
                    }""".replace("\\s+".toRegex(), " ")

                    path == "/api/quickstart/state" -> """{"quickstart_completed":false,"agents":[]}"""
                    path == "/api/quickstart/fields" -> """[{"key":"model_provider","label":"AI Provider","type":"select","required":true,"options":[{"value":"manual","label":"Manual Setup"}]},{"key":"api_key","label":"API Key","type":"password","required":false}]"""
                    path == "/api/agents" -> """[{"alias":"default","name":"Default Agent","status":"idle"}]"""
                    path.startsWith("/api/agent/") && parts[0] == "POST" && path.endsWith("/chat") -> """{"role":"assistant","content":"Gateway active. Configure an API key in Settings to enable chat."}"""
                    path.startsWith("/api/agent/") -> {
                        val a = path.removePrefix("/api/agent/").split("/").first()
                        """{"alias":"$a","status":"idle"}"""
                    }
                    path == "/api/tools" -> "[]"
                    path == "/api/config" -> """[{"name":"gateway","label":"Gateway","sections":[{"name":"gateway","keys":[{"key":"port","value":18789,"type":"integer"}]}]}]"""
                    path.startsWith("/api/config/") -> """{"name":"","keys":[]}"""
                    path == "/api/logs" -> "[]"
                    path == "/api/cron" -> "[]"
                    path == "/api/integrations" -> """[{"platform":"embedded","enabled":true,"status":"active","label":"Embedded Gateway"}]"""
                    path == "/api/doctor" -> """[{"id":"gateway","title":"Gateway","status":"pass","message":"Running"}]"""
                    path == "/api/admin/reload" -> """{"success":true}"""
                    path == "/api/admin/pair/code" -> """{"pairing_code":"000000"}"""
                    path == "/api/admin/pair" && parts[0] == "POST" -> {
                        val code = """"code"\s*:\s*"([^"]+)"""".toRegex().find(body)?.groupValues?.getOrNull(1) ?: ""
                        if (code.isNotBlank()) """{"token":"embedded-token","success":true}""" else """{"success":false,"error":"No code"}"""
                    }
                    path == "/api/quickstart/apply" -> """{"success":true}"""
                    path == "/api/quickstart/dismiss" -> """{"success":true}"""
                    else -> "HTTP/1.1 404\r\nContent-Length: 0\r\n\r\n"
                }

                val out = if (resp.startsWith("HTTP/1.1")) resp
                else "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n$resp"
                w.write(out.toByteArray()); w.flush()
            }
        } catch (_: Exception) {}
    }
}
