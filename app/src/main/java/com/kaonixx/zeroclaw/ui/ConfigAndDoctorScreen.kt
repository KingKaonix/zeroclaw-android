package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.DiagResult
import com.kaonixx.zeroclaw.model.ConfigCategory
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun DoctorScreen() {
    var results by remember { mutableStateOf<List<DiagResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() { scope.launch { try { results = ApiClient.getDiagnostics() } catch (e: Exception) { error = e.message }; loading = false } }
    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(error!!, ::load)
        loading -> LoadingIndicator("Running diagnostics...")
        results.isEmpty() -> EmptyState(Icons.Default.PestControl, "Diagnostics passed", "System appears healthy")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Header("Diagnostics") }
            items(results, key = { it.id }) { r ->
                val c = when (r.status) { "ok" -> GreenAccent; "warn" -> AmberAccent; "fail" -> RoseAccent else -> TextMuted }
                GlassCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(r.message, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            r.details?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted) }
                        }
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = c.copy(alpha = 0.2f)) {
                            Text(r.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigScreen(onSectionClick: (String) -> Unit) {
    var config by remember { mutableStateOf<List<ConfigCategory>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() { scope.launch { try { config = ApiClient.getConfig() } catch (e: Exception) { error = e.message }; loading = false } }
    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(error!!, ::load)
        loading -> LoadingIndicator("Loading config...")
        config.isEmpty() -> EmptyState(Icons.Default.Settings, "No configuration", "Start daemon to see config")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Header("Config") }
            items(config, key = { it.name }) { cat ->
                Column {
                    cat.label?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = CyanAccent, modifier = Modifier.padding(bottom = 6.dp)) }
                    cat.sections.forEach { section ->
                        SectionCard(title = section.label ?: section.name, subtitle = "keys: ${section.keys.size} values") {
                            onSectionClick(section.name)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

private fun Pair<String, String>.toString() = "$first: $second"
