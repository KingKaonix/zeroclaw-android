package com.kaonixx.zeroclaw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.theme.*

@Composable
fun GlassCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceCard,
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun StatusDot(status: String) {
    val c = when (status) {
        "idle" -> TextMuted; "running" -> GreenAccent; "error" -> RoseAccent
        "busy" -> AmberAccent; "paused" -> AmberAccent else -> TextMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = c)
    }
}

@Composable
fun Header(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge,
        color = TextPrimary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun EmptyState(icon: ImageVector = Icons.Default.Info, title: String, subtitle: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = TextMuted, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextMuted)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, tint = RoseAccent, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Connection Error", style = MaterialTheme.typography.titleMedium, color = RoseAccent)
            Text(message.take(120), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)) {
                Text("Retry", color = DeepCharcoal)
            }
        }
    }
}

@Composable
fun LoadingIndicator(message: String = "Loading...") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = CyanAccent, strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextMuted)
        }
    }
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceCard
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
            }
            if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
        }
    }
}
