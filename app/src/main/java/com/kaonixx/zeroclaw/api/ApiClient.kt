package com.kaonixx.zeroclaw.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kaonixx.zeroclaw.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiClient {
    const val GUMROAD_URL = "https://mulikjo.gumroad.com/l/zeroclaw-android"

    private const val BASE = "http://127.0.0.1:18789/api"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    var authToken: String? = null
        private set

    fun setToken(token: String) { authToken = token }
    fun clearToken() { authToken = null }

    private fun build(path: String): Request.Builder {
        val b = Request.Builder().url("$BASE$path")
        authToken?.let { b.addHeader("Authorization", "Bearer $it") }
        return b
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val req = build(path).get().build()
        val res = client.newCall(req).execute()
        val body = res.body?.string() ?: throw Exception("Empty response")
        if (!res.isSuccessful) throw Exception("HTTP ${res.code}: $body")
        gson.fromJson(body, object : TypeToken<T>() {}.type)
    }

    private suspend inline fun <reified T> post(path: String, body: Any? = null): T =
        withContext(Dispatchers.IO) {
            val jsonBody = body?.let { gson.toJson(it).toRequestBody(JSON) }
            val req = build(path).post(jsonBody ?: "{}".toRequestBody(JSON)).build()
            val res = client.newCall(req).execute()
            val bodyStr = res.body?.string() ?: throw Exception("Empty response")
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}: $bodyStr")
            gson.fromJson(bodyStr, object : TypeToken<T>() {}.type)
        }

    // Status
    suspend fun getStatus() = get<StatusResponse>("/status")

    // Pairing
    suspend fun getPairingCode() = get<PairingCodeResponse>("/admin/pair/code")
    suspend fun pair(code: String): AuthResponse {
        return withContext(Dispatchers.IO) {
            val jsonBody = """{"code":"$code"}""".toRequestBody(JSON)
            val req = build("/admin/pair").post(jsonBody).build()
            val res = client.newCall(req).execute()
            val body = res.body?.string() ?: throw Exception("Empty response")
            if (!res.isSuccessful) throw Exception("Pairing failed: HTTP ${res.code}")
            gson.fromJson(body, AuthResponse::class.java)
        }
    }

    // Agents
    suspend fun getAgents() = get<List<AgentInfo>>("/agents")
    suspend fun getAgent(alias: String) = get<AgentInfo>("/agent/$alias")
    suspend fun getAgentMessages(alias: String) =
        get<List<ChatMessage>>("/agent/$alias/messages")
    suspend fun sendMessage(alias: String, content: String): ChatMessage =
        post("/agent/$alias/chat", mapOf("content" to content))

    // Tools
    suspend fun getTools() = get<List<ToolSpec>>("/tools")

    // Config
    suspend fun getConfig() = get<List<ConfigCategory>>("/config")
    suspend fun getConfigSection(section: String) = get<ConfigSection>("/config/$section")

    // Logs
    suspend fun getLogs(since: String? = null, level: String? = null) =
        get<List<LogEntry>>("/logs")

    // Cron
    suspend fun getCronJobs() = get<List<CronJob>>("/cron")

    // Integrations
    suspend fun getIntegrations() = get<List<Integration>>("/integrations")

    // Doctor
    suspend fun getDiagnostics() = get<List<DiagResult>>("/doctor")

    // Quickstart
    suspend fun getQuickstartState() = get<QuickstartState>("/quickstart/state")
    suspend fun getQuickstartFields() = get<List<QuickstartField>>("/quickstart/fields")
    suspend fun applyQuickstart(req: QuickstartApplyRequest): QuickstartApplyResponse =
        post("/quickstart/apply", req)
    suspend fun dismissQuickstart(): QuickstartApplyResponse =
        post("/quickstart/dismiss")
    suspend fun reloadDaemon(): String =
        post("/admin/reload")

    // Health
    suspend fun getHealth() = get<HealthSnapshot>("/health")
}
