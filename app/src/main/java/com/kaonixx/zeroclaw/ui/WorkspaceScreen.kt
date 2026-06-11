package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*

@Composable
fun WorkspaceScreen() = WorkspaceExplorerScreen(alias = "default", onBack = {})

@Composable
fun WorkspaceExplorerScreen(alias: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SurfaceBase)) {
        Row(Modifier.fillMaxWidth().padding(8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("Workspace", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(alias, style = MaterialTheme.typography.labelSmall, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Header("Agent Workspace") }
            item { WorkspaceRow("/", "Root workspace", true) }
            item { WorkspaceRow("README.md", "Project notes", false) }
            item { WorkspaceRow("src/", "Source tree", true) }
            item { WorkspaceRow("logs/", "Run outputs", true) }
        }
    }
}

@Composable
private fun WorkspaceRow(name: String, subtitle: String, dir: Boolean) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (dir) Icons.Default.Folder else Icons.Default.Description, null, tint = if (dir) CyanAccent else TextMuted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun QuickstartScreen() {
    LazyColumn(Modifier.fillMaxSize().background(SurfaceBase), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header("Quickstart") }
        item { SectionCard("1. Pair daemon", "Open pairing, paste device token") }
        item { SectionCard("2. Pick agent", "Agents → select alias") }
        item { SectionCard("3. Chat / workspace", "Send prompts, inspect files") }
        item { SectionCard("4. Automation", "Cron, tools, logs, doctor") }
    }
}

@Composable
fun ConfigSectionScreen(section: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SurfaceBase)) {
        Row(Modifier.fillMaxWidth().padding(8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
            Text(section, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
        Box(Modifier.fillMaxSize().padding(16.dp)) { SectionCard("$section config", "Read-only config section") }
    }
}
