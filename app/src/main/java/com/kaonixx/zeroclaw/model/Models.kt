package com.kaonixx.zeroclaw.model

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    val version: String? = null,
    @SerializedName("model_provider") val modelProvider: String? = null,
    val model: String = "",
    val temperature: Double = 0.0,
    @SerializedName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerializedName("daemon_started_at") val daemonStartedAt: String? = null,
    @SerializedName("gateway_port") val gatewayPort: Int = 18789,
    val locale: String = "en",
    @SerializedName("memory_backend") val memoryBackend: String = "",
    val paired: Boolean = false,
    val channels: Map<String, Boolean> = emptyMap(),
    val health: HealthSnapshot = HealthSnapshot(),
    val process: ProcessStats? = null
)

data class ProcessStats(
    @SerializedName("rss_bytes") val rssBytes: Long = 0,
    @SerializedName("system_ram_total_bytes") val systemRamTotalBytes: Long = 0,
    @SerializedName("cpu_percent") val cpuPercent: Double? = null,
    @SerializedName("num_cpus") val numCpus: Int = 0
)

data class HealthSnapshot(
    val pid: Int = 0,
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("uptime_seconds") val uptimeSeconds: Long = 0,
    val components: Map<String, ComponentHealth> = emptyMap()
)

data class ComponentHealth(
    val status: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("last_ok") val lastOk: String? = null,
    @SerializedName("last_error") val lastError: String? = null,
    @SerializedName("restart_count") val restartCount: Int = 0
)

data class ToolSpec(
    val name: String = "",
    val description: String = "",
    val parameters: Any? = null
)

data class CronJob(
    val id: String = "",
    val name: String? = null,
    val expression: String = "",
    val command: String = "",
    val prompt: String? = null,
    @SerializedName("job_type") val jobType: String = "",
    val schedule: CronSchedule = CronSchedule(),
    val enabled: Boolean = true,
    val delivery: CronDeliveryConfig = CronDeliveryConfig(),
    @SerializedName("delete_after_run") val deleteAfterRun: Boolean = false,
    @SerializedName("session_target") val sessionTarget: String? = null,
    val model: String? = null,
    @SerializedName("allowed_tools") val allowedTools: List<String>? = null,
    val source: String? = null,
    @SerializedName("agent_alias") val agentAlias: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("next_run") val nextRun: String = "",
    @SerializedName("last_run") val lastRun: String? = null,
    @SerializedName("last_status") val lastStatus: String? = null,
    @SerializedName("last_output") val lastOutput: String? = null
)

data class CronSchedule(
    val kind: String = "every",
    val expr: String? = null,
    val tz: String? = null,
    val at: String? = null,
    @SerializedName("every_ms") val everyMs: Long? = null
)

data class CronDeliveryConfig(
    val mode: String = "",
    val channel: String? = null,
    val to: String? = null,
    @SerializedName("best_effort") val bestEffort: Boolean? = null
)

data class CronRun(
    val id: Int = 0,
    @SerializedName("job_id") val jobId: String = "",
    @SerializedName("started_at") val startedAt: String = "",
    @SerializedName("finished_at") val finishedAt: String = "",
    val status: String = "",
    val output: String? = null,
    @SerializedName("duration_ms") val durationMs: Long? = null
)

data class Integration(
    val platform: String = "",
    val enabled: Boolean = false,
    val status: String = "",
    val label: String? = null
)

data class DiagResult(
    val id: String = "",
    val title: String = "",
    val status: String = "",
    val message: String = "",
    val details: String? = null
)

data class AgentInfo(
    val alias: String = "",
    val name: String? = null,
    val model: String = "",
    val status: String = "idle",
    val provider: String? = null,
    val description: String? = null
)

data class ChatMessage(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val timestamp: String? = null,
    val agent_alias: String? = null
)

data class LogEntry(
    val timestamp: String = "",
    val level: String = "",
    val message: String = "",
    val target: String? = null
)

data class ConfigCategory(
    val name: String = "",
    val label: String? = null,
    val description: String? = null,
    val sections: List<ConfigSection> = emptyList()
)

data class ConfigSection(
    val name: String = "",
    val label: String? = null,
    val type: String = "section",
    val keys: List<ConfigKey> = emptyList()
)

data class ConfigKey(
    val key: String = "",
    val value: Any? = null,
    val default: Any? = null,
    val description: String? = null,
    val type: String = "string"
)

data class PairingCodeResponse(
    @SerializedName("pairing_code") val pairingCode: String? = null
)

data class AuthResponse(
    val token: String = "",
    val success: Boolean = false
)

data class QuickstartState(
    @SerializedName("quickstart_completed") val quickstartCompleted: Boolean = false,
    val agents: List<AgentInfo> = emptyList()
)
