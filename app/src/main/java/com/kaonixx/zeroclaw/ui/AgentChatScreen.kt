package com.kaonixx.zeroclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.components.*

@Composable
fun AgentChatScreen(alias: String, onBack: () -> Unit, onWorkspace: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SurfaceBase)) {
        Row(Modifier.fillMaxWidth().padding(8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text(alias, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Agent chat", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            IconButton(onClick = onWorkspace) { Icon(Icons.Default.FolderOpen, "Workspace", tint = CyanAccent) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header("Chat") }
            item { GlassCard { Text("Connected to $alias", color = TextPrimary) } }
            item { GlassCard { Text("Message composer/API streaming will attach here.", color = TextMuted) } }
        }
        Surface(color = DeepCharcoal, tonalElevation = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…", color = TextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledBorderColor = BorderDim,
                        disabledContainerColor = SurfaceElevated,
                        disabledTextColor = TextMuted,
                        disabledPlaceholderColor = TextMuted
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = {}, enabled = false) { Text("→") }
            }
        }
    }
}
