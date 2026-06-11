package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.AgentInfo
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun AgentsListScreen(onAgentClick: (String) -> Unit) {
    var agents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() { scope.launch { loading = true
        try { agents = ApiClient.getAgents(); loading = false }
        catch (e: Exception) { error = e.message; loading = false }
    } }
    LaunchedEffect(Unit) { load() }

    when {
        error != null -> ErrorView(error!!, ::load)
        loading -> LoadingIndicator("Loading agents...")
        agents.isEmpty() -> EmptyState(Icons.Default.SmartToy, "No agents", "Configure agents via CLI")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Header("Agents") }
            items(agents, key = { it.alias }) { agent ->
                GlassCard(onClick = { onAgentClick(agent.alias) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(agent.name ?: agent.alias,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            agent.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            StatusDot(agent.status)
                            Spacer(Modifier.height(4.dp))
                            Text(agent.model.take(20), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}
