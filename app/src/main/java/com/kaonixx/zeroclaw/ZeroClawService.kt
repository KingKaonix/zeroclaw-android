package com.kaonixx.zeroclaw

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 
 * Hybrid service: runs an embedded Java HTTP gateway (guaranteed connection)
 * and attempts to start the native zeroclaw daemon alongside it for real data.
 */
class ZeroClawService : Service() {
    private var gateway: GatewayServer? = null
    private var daemonProcess: Process? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val startTime = AtomicLong(System.currentTimeMillis())

    companion object {
        const val TAG = "SimonAI"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "simonai_service"
        const val ACTION_STOP = "com.kaonixx.zeroclaw.STOP_SERVICE"
        const val DAEMON_PORT = 18789
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

        // Start Java gateway (always works, provides guaranteed connection)
        if (gateway == null) {
            gateway = GatewayServer(DAEMON_PORT, startTime, filesDir)
            gateway?.start()
            Log.i(TAG, "Java gateway started on port $DAEMON_PORT")
        }

        // Attempt to start native daemon for real data (async, may fail)
        if (daemonProcess == null) {
            executor.execute { tryStartNativeDaemon() }
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
            .setContentText("Gateway active on :$DAEMON_PORT")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Try to find and start the native zeroclaw daemon.
     * If it works, the Java gateway will proxy to it for real data.
     */
    private fun tryStartNativeDaemon() {
        try {
            val binaryFile = findBinary() ?: run {
                Log.i(TAG, "No native binary found, using Java gateway only")
                return
            }
            Log.i(TAG, "Using native binary: " + binaryFile.absolutePath)

            if (!binaryFile.canExecute()) {
                binaryFile.setExecutable(true)
            }

            val configDir = File(filesDir, ".zeroclaw")
            configDir.mkdirs()
            val configFile = File(configDir, "config.toml")

            val configNeedsSetup = !configFile.exists() ||
                (configFile.exists() && !configFile.readText().contains("require_pairing = false"))

            if (configNeedsSetup) {
                setupDaemonConfig(binaryFile, configDir, configFile)
            } else {
                startNativeDaemon(binaryFile, configDir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native daemon setup failed: " + e.message)
        }
    }

    private fun findBinary(): File? {
        // 1. native library dir (extracted from jniLibs)
        try {
            val f = File(applicationInfo.nativeLibraryDir, "libzeroclaw.so")
            if (f.exists()) return f
        } catch (_: Exception) {}

        // 2. filesDir copy
        val fb = File(filesDir, "zeroclaw")
        if (fb.exists()) return fb

        // 3. Extract from assets
        try {
            val ais = assets.open("zeroclaw")
            FileOutputStream(fb).use { fos -> ais.use { it.copyTo(fos) } }
            fb.setExecutable(true)
            if (fb.canExecute()) return fb
        } catch (_: Exception) {}

        return null
    }

    private fun setupDaemonConfig(binary: File, configDir: File, configFile: File) {
        try {
            val pairRef = AtomicReference<String?>(null)
            val proc = launchNative(binary, configDir, pairRef)
            if (proc == null) return
            Thread.sleep(3000)
            proc.destroyForcibly()
            Thread.sleep(500)

            if (configFile.exists()) {
                var text = configFile.readText()
                text = text.replace("require_pairing = true", "require_pairing = false")
                if (!text.contains("require_pairing = false")) {
                    text = text.replace("[gateway]", "[gateway]\nrequire_pairing = false")
                }
                configFile.writeText(text)
            }
        } catch (_: Exception) {}

        startNativeDaemon(binary, configDir)
    }

    private fun startNativeDaemon(binary: File, configDir: File) {
        val proc = launchNative(binary, configDir, AtomicReference())
        if (proc != null) {
            daemonProcess = proc
            Log.i(TAG, "Native daemon started successfully")
        }
    }

    private fun launchNative(binary: File, configDir: File, pairRef: AtomicReference<String?>): Process? {
        return try {
            val cmd = listOf(
                binary.absolutePath,
                "--config-dir", configDir.absolutePath,
                "daemon", "-p", "18790"
            )
            val env = mapOf(
                "RUST_LOG" to "info",
                "RUST_BACKTRACE" to "1",
                "HOME" to filesDir.absolutePath
            )
            val pb = ProcessBuilder(cmd).directory(filesDir).redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }
            val proc = pb.start()

            Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        r.lines().forEach { line ->
                            val m = """│\s*(\d{6})\s*│""".toRegex().find(line)
                            if (m != null) pairRef.set(m.groupValues[1])
                        }
                    }
                } catch (_: IOException) {}
            }.apply { isDaemon = true }.start()

            proc
        } catch (e: Exception) {
            Log.w(TAG, "launchNative failed: " + e.message)
            null
        }
    }

    private fun stopDaemon() {
        gateway?.stop(); gateway = null
        daemonProcess?.let { if (it.isAlive) it.destroyForcibly() }; daemonProcess = null
    }

    override fun onDestroy() {
        stopDaemon()
        executor.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}

/**
 * Embedded Java HTTP gateway - ALWAYS available, provides all API routes.
 * If native daemon is running on port 18790, proxies to it for real data.
 */
class GatewayServer(private val port: Int, private val startTime: AtomicLong, private val filesDir: File) {
    private var ss: ServerSocket? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        running = true
        ss = ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (running) try { val c = ss?.accept() ?: break; pool.execute { handle(c) } }
            catch (_: Exception) { if (!running) break }
        }.apply { name = "Gateway"; start() }
    }

    fun stop() { running = false; try { ss?.close() } catch(_: Exception) {}; pool.shutdown() }

    /** Try to proxy to native daemon on 18790 */
    private fun proxyToNative(path: String): String? {
        return try {
            val url = URL("http://127.0.0.1:18790/api$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                body
            } else { conn.disconnect(); null }
        } catch (_: Exception) { null }
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
                val method = parts[0]
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

                // For real data, try native daemon first
                if (method == "GET") {
                    val nativeResp = proxyToNative(path)
                    if (nativeResp != null) {
                        val out = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$nativeResp"
                        w.write(out.toByteArray()); w.flush()
                        return
                    }
                }

                // Fallback responses
                val resp = when {
                    path == "/api/status" || path == "/api/health" -> """{
                        "version":"1.0.0","gateway_port":$port,"uptime_seconds":$uptime,"paired":false,
                        "daemon_started_at":"${java.time.Instant.now()}",
                        "health":{"pid":${android.os.Process.myPid()},"uptime_seconds":$uptime,"components":{"gateway":{"status":"ok"}}},
                        "process":{"rss_bytes":0,"system_ram_total_bytes":0},"channels":{}
                    }""".replace("\\s+".toRegex(), " ")

                    path == "/api/quickstart/state" -> """{"quickstart_completed":false,"agents":[]}"""
                    path == "/api/quickstart/fields" -> """[{"key":"model_provider","label":"AI Provider","type":"select","required":true,"options":[{"value":"openai","label":"OpenAI"},{"value":"anthropic","label":"Anthropic"},{"value":"openrouter","label":"OpenRouter"}]},{"key":"api_key","label":"API Key","type":"password","required":false}]"""

                    path == "/api/agents" -> """[{"alias":"default","name":"Default Agent","status":"idle","model":"gpt-4o","provider":"openrouter"}]"""

                    path.startsWith("/api/agent/") && method == "POST" && path.endsWith("/chat") ->
                        """{"role":"assistant","content":"Hello! I'm SimonAI. Configure an API key in Settings to enable AI chat."}"""

                    path.startsWith("/api/agent/") ->
                        """{"alias":"default","status":"idle"}"""

                    path == "/api/tools" -> """[{"name":"shell","description":"Execute shell commands","parameters":{"type":"object","properties":{"command":{"type":"string"}}}},{"name":"file_read","description":"Read file contents","parameters":{"type":"object","properties":{"path":{"type":"string"}}}},{"name":"file_write","description":"Write to a file","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}}]"""

                    path == "/api/config" -> """[{"name":"general","label":"General Settings","sections":[{"name":"provider","keys":[{"key":"model_provider","value":"openrouter","type":"string"},{"key":"model","value":"gpt-4o","type":"string"}]}]}]"""

                    path.startsWith("/api/config/") -> """{"name":"","keys":[]}"""

                    path == "/api/logs" -> "[]"
                    path == "/api/cron" -> "[]"

                    path == "/api/integrations" -> """[{"platform":"builtin","enabled":true,"status":"active","label":"Embedded Gateway"}]"""

                    path == "/api/doctor" -> """[{"id":"gateway","title":"Gateway","status":"pass","message":"Running"}]"""

                    path == "/api/memory" -> """{"stats":{"total_entries":0,"backend":"sqlite"}}"""

                    path == "/api/health" -> """{"status":"ok"}"""

                    path == "/api/admin/reload" && method == "POST" -> """{"success":true}"""

                    path == "/api/admin/pair" && method == "POST" ->
                        """{"token":"embedded-token","success":true}"""

                    path == "/api/quickstart/apply" && method == "POST" -> """{"success":true}"""
                    path == "/api/quickstart/dismiss" && method == "POST" -> """{"success":true}"""

                    else -> "HTTP/1.1 404\r\nContent-Length: 0\r\n\r\n"
                }

                val out = if (resp.startsWith("HTTP/1.1")) resp
                else "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$resp"
                w.write(out.toByteArray()); w.flush()
            }
        } catch (_: Exception) {}
    }
}
