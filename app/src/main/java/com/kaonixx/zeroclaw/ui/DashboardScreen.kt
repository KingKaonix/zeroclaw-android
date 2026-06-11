package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.StatusResponse
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen() {
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true; error = null
        scope.launch {
            try { status = ApiClient.getStatus(); loading = false }
            catch (e: Exception) { error = e.message; loading = false }
        }
    }

    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(message = error!!, onRetry = ::load)
        loading -> LoadingIndicator(message = "Syncing with daemon...")
        status == null -> LoadingIndicator()
        else -> DashboardContent(status!!)
    }
}

@Composable
private fun DashboardContent(status: StatusResponse) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SurfaceBase),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Header("System Overview")
        }

        // Bento Grid Mockup: Since Compose LazyColumn is linear, 
        // we use Rows to simulate the Bento spatial rhythm
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Primary Hero Card (Wide)
                Box(Modifier.weight(1.5f)) {
                    GlassCard {
                        Column {
                            Text("SimonAI", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(status.model, style = MaterialTheme.typography.bodySmall, color = CyanAccent)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                InfoItem("Uptime", formatUptime(status.uptimeSeconds))
                                InfoItem("Gateway", "${status.gatewayPort}")
                            }
                        }
                    }
                }
                // Metric Card (Tall/Narrow)
                Box(Modifier.weight(1f)) {
                    GlassCard {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Memory, null, tint = CyanAccent, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("RAM", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(status.process?.let { "${it.rssBytes / 1024 / 1024} MB" } ?: "N/A", 
                                style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Process Stats Row
        status.process?.let { proc ->
            item {
                GlassCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        MetricItem("CPU", "${proc.cpuPercent?.toInt() ?: 0}%", Icons.Default.Speed)
                        MetricItem("Cores", "${proc.numCpus}", Icons.Default.Settings)
                        MetricItem("Paired", if (status.paired) "Yes" else "No", Icons.Default.CheckCircle)
                    }
                }
            }
        }

        // Components Section
        item {
            Text("System Health", style = MaterialTheme.typography.titleMedium, color = TextSecondary, fontWeight = FontWeight.SemiBold)
        }

        status.health.components.forEach { (name, health) ->
            item {
                GlassCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(health.status)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            health.lastError?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = RoseAccent)
                            }
                        }
                        Text(health.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MetricItem(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return "${h}h ${m}m ${s}s"
}
