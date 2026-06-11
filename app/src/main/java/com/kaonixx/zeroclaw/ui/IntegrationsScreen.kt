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
import com.kaonixx.zeroclaw.model.Integration
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun IntegrationsScreen() {
    var integrations by remember { mutableStateOf<List<Integration>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() { scope.launch { try { integrations = ApiClient.getIntegrations() } catch (e: Exception) { error = e.message }; loading = false } }
    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(error!!, ::load)
        loading -> LoadingIndicator("Loading integrations...")
        integrations.isEmpty() -> EmptyState(Icons.Default.Link, "No integrations", "Configure integrations via CLI")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Header("Integrations") }
            items(integrations, key = { it.platform }) { i ->
                GlassCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(i.platform.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = if (i.enabled) GreenAccent.copy(alpha = 0.2f) else SurfaceElevated) {
                            Text(if (i.enabled) "Active" else "Off", style = MaterialTheme.typography.labelSmall, color = if (i.enabled) GreenAccent else TextMuted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}