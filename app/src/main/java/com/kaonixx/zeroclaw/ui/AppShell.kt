package com.kaonixx.zeroclaw.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import android.content.Intent
import android.net.Uri
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.navigation.NavGraph
import com.kaonixx.zeroclaw.navigation.Screen
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.LicenseValidator

data class NavItem(val screen: Screen, val icon: ImageVector, val label: String)

val navItems = listOf(
    NavItem(Screen.Dashboard, Icons.Default.Home, "Home"),
    NavItem(Screen.AgentsList, Icons.Default.SmartToy, "Agents"),
    NavItem(Screen.Tools, Icons.Default.Build, "Tools"),
    NavItem(Screen.Logs, Icons.Default.Terminal, "Logs"),
    NavItem(Screen.Config, Icons.Default.Settings, "Config"),
)

val moreItems = listOf(
    NavItem(Screen.Cron, Icons.Default.Schedule, "Cron"),
    NavItem(Screen.Integrations, Icons.Default.Link, "Integrations"),
    NavItem(Screen.Doctor, Icons.Default.PestControl, "Doctor"),
    NavItem(Screen.Canvas, Icons.Default.Dashboard, "Canvas"),
    NavItem(Screen.Quickstart, Icons.Default.Rocket, "Quickstart"),
)

@Composable
fun AppShell(context: Context, isPaired: Boolean, onPair: suspend (String) -> Unit) {
    val navController = rememberNavController()
    var showMore by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(SurfaceBase)) {
        // Atmospheric Nebula background
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.size(400.dp).align(Alignment.TopStart).offset(x = (-100).dp, y = (-100).dp)
                .background(CyanAccent.copy(alpha = 0.1f), RoundedCornerShape(100.dp)).blur(120.dp))
            Box(Modifier.size(400.dp).align(Alignment.BottomEnd).offset(x = 100.dp, y = 100.dp)
                .background(PurpleAccent.copy(alpha = 0.1f), RoundedCornerShape(100.dp)).blur(120.dp))
        }

        NavGraph(navController = navController, isPaired = isPaired, onPair = onPair)

        if (isPaired) {
            // Floating Glass Nav Pill
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                Surface(
                    modifier = Modifier
                        .wrapContentSize()
                        .clip(RoundedCornerShape(40.dp))
                        .background(DeepCharcoal.copy(alpha = 0.8f)),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination

                        navItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(if (selected) CyanAccent.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        navController.navigate(item.screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true; restoreState = true
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(item.icon, item.label, tint = if (selected) CyanAccent else TextMuted, modifier = Modifier.size(24.dp))
                            }
                        }
                        
                        Spacer(Modifier.width(4.dp))
                        
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(32.dp))
                                .background(if (showMore) CyanAccent.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { showMore = !showMore }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (showMore) Icons.Default.Close else Icons.Default.MoreHoriz, "More", tint = if (showMore) CyanAccent else TextMuted, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        // More Drawer - Double Bezel
        AnimatedVisibility(
            visible = showMore && isPaired,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).padding(bottom = 80.dp),
                color = DeepCharcoal.copy(alpha = 0.9f),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Extended Controls", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    moreItems.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { item ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                    navController.navigate(item.screen.route)
                                    showMore = false
                                }, verticalArrangement = Arrangement.Center) {
                                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceElevated).padding(12.dp)) {
                                        Icon(item.icon, item.label, tint = CyanAccent)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BorderDim, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                val isPro = LicenseValidator.isPro(context)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isPro) "SimonAI Pro" else "SimonAI Free",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (isPro) "Licensed" else "Watermark active",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    if (!isPro) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ApiClient.GUMROAD_URL))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Get Pro", color = DeepCharcoal, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
