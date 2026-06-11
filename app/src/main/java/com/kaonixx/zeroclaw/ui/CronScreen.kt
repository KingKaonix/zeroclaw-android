package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.CronJob
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CronScreen() {
    var jobs by remember { mutableStateOf<List<CronJob>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() { scope.launch { try { jobs = ApiClient.getCronJobs() } catch (e: Exception) { error = e.message }; loading = false } }
    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(error!!, ::load)
        loading -> LoadingIndicator("Loading jobs...")
        jobs.isEmpty() -> EmptyState(Icons.Default.Schedule, "No cron jobs", "Configure jobs via CLI or API")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Header("Cron Jobs (${jobs.size})") }
            items(jobs, key = { it.id }) { job -> CronJobCard(job) }
        }
    }
}

@Composable
private fun CronJobCard(job: CronJob) {
    val statusColor = when (job.lastStatus) {
        "success" -> GreenAccent; "failed" -> RoseAccent; "running" -> CyanAccent else -> TextMuted
    }
    GlassCard {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.name ?: job.id.take(16), style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(scheduleLabel(job), style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                }
                statusColor.let { Box(Modifier.size(8.dp).background(it, RoundedCornerShape(4.dp))) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                job.nextRun.takeIf { it.isNotBlank() }?.let {
                    Text("Next: ${formatTs(it)}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                job.lastStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = statusColor) }
            }
        }
    }
}

private fun scheduleLabel(job: CronJob): String = when (job.schedule.kind) {
    "cron" -> job.schedule.expr ?: ""
    "every" -> job.schedule.everyMs?.let { "every ${it/1000}s" } ?: "every"
    else -> job.schedule.kind
}

private fun formatTs(ts: String): String = try {
    Instant.parse(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
} catch (_: Exception) { ts.take(16) }