package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.LogEntry
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun LogsScreen() {
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() { scope.launch { try { logs = ApiClient.getLogs() } catch (e: Exception) { error = e.message }; loading = false } }
    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(error!!, ::load)
        loading -> LoadingIndicator("Loading logs...")
        logs.isEmpty() -> EmptyState(Icons.Default.Terminal, "No logs", "Enable logging in config")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { Header("Logs") }
            items(logs, key = { "${it.timestamp}-${it.hashCode()}" }) { entry ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = SurfaceElevated.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.level.uppercase(), style = MaterialTheme.typography.labelSmall, color = logLevelColor(entry.level), fontWeight = FontWeight.Bold)
                            entry.target?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.message, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
                        Text(entry.timestamp.take(19), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
            }
        }
    }
}

private fun logLevelColor(level: String) = when (level.lowercase()) {
    "error" -> RoseAccent; "warn" -> AmberAccent; "info" -> CyanAccent; "debug" -> TextMuted else -> TextPrimary
}