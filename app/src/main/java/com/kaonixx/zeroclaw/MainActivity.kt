package com.kaonixx.zeroclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.DaemonStartProgress
import com.kaonixx.zeroclaw.model.DaemonStartStep
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.AppShell
import com.kaonixx.zeroclaw.ui.screens.OnboardingScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppUiState { StartingDaemon, DaemonError, Onboarding, Ready }

class MainActivity : AppCompatActivity() {
    companion object {
        const val LICENSE_SERVER_URL = "https://zeroclaw-license.joemulik.workers.dev"
    }

    private val NOTIF_PERM_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        startService(Intent(this, ZeroClawService::class.java))

        setContent {
            ZeroClawTheme {
                var appState by remember { mutableStateOf(AppUiState.StartingDaemon) }
                var isPaired by remember { mutableStateOf(false) }
                var retryTrigger by remember { mutableStateOf(0) }
                var progress by remember { mutableStateOf(DaemonStartProgress()) }
                val scope = rememberCoroutineScope()

                // Poll for daemon with progress tracking
                LaunchedEffect(retryTrigger) {
                    appState = AppUiState.StartingDaemon
                    progress = DaemonStartProgress(
                        step = DaemonStartStep.ExtractingBinary,
                        message = "Preparing agent binary...",
                        progress = 0.1f
                    )

                    // Simulate startup steps
                    delay(800)
                    progress = DaemonStartProgress(
                        step = DaemonStartStep.StartingDaemon,
                        message = "Starting SimonAI daemon...",
                        progress = 0.3f
                    )

                    val ready = pollDaemon(progressUpdater = { p, msg ->
                        progress = DaemonStartProgress(
                            step = DaemonStartStep.WaitingForGateway,
                            message = msg,
                            progress = p
                        )
                    })

                    if (ready) {
                        progress = DaemonStartProgress(
                            step = DaemonStartStep.DaemonReady,
                            message = "Daemon connected!",
                            progress = 1f
                        )
                        delay(300)

                        // Check if onboarding is needed
                        try {
                            val qs = ApiClient.getQuickstartState()
                            if (qs.quickstartCompleted) {
                                val status = ApiClient.getStatus()
                                isPaired = status.paired
                                appState = AppUiState.Ready
                            } else {
                                appState = AppUiState.Onboarding
                            }
                        } catch (_: Exception) {
                            // Can't determine quickstart state — go to main app
                            try {
                                val status = ApiClient.getStatus()
                                isPaired = status.paired
                            } catch (_: Exception) {}
                            appState = AppUiState.Ready
                        }
                    } else {
                        progress = DaemonStartProgress(
                            step = DaemonStartStep.Failed,
                            message = "Could not connect to daemon",
                            progress = 0f
                        )
                        appState = AppUiState.DaemonError
                    }
                }

                when (appState) {
                    AppUiState.StartingDaemon -> StartupScreen(progress)
                    AppUiState.DaemonError -> DaemonErrorScreen(
                        onRetry = { retryTrigger++ },
                        progress = progress
                    )
                    AppUiState.Onboarding -> OnboardingScreen(
                        onComplete = {
                            // After onboarding, reload daemon and go to app
                            scope.launch {
                                try {
                                    ApiClient.reloadDaemon()
                                    delay(1000)
                                    val status = ApiClient.getStatus()
                                    isPaired = status.paired
                                } catch (_: Exception) {}
                                appState = AppUiState.Ready
                            }
                        }
                    )
                    AppUiState.Ready -> AppShell(
                        context = this@MainActivity,
                        isPaired = isPaired,
                        onPair = { code ->
                            val response = ApiClient.pair(code)
                            if (response.success) {
                                ApiClient.setToken(response.token)
                                isPaired = true
                            } else {
                                throw Exception("Pairing rejected by daemon")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERM_CODE
                )
            }
        }
    }

    private suspend fun pollDaemon(progressUpdater: (Float, String) -> Unit): Boolean {
        repeat(30) { i ->
            val p = 0.3f + (i.toFloat() / 30f) * 0.65f
            val msgs = listOf(
                "Connecting to gateway...",
                "Establishing API connection...",
                "Waiting for daemon response...",
                "Almost there..."
            )
            progressUpdater(p, msgs.getOrElse(i / 8) { "Connecting... (${i + 1}/30)" })
            delay(1000)
            try {
                ApiClient.getStatus()
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    @Composable
    private fun StartupScreen(progress: DaemonStartProgress) {
        Box(
            modifier = Modifier.fillMaxSize().background(SurfaceBase),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                // Logo
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = CyanAccentGlow
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "S",
                            style = MaterialTheme.typography.headlineLarge,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    "SimonAI",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )

                Spacer(Modifier.height(24.dp))

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(SurfaceElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (progress.step == DaemonStartStep.Failed) RoseAccent
                                else CyanAccent
                            )
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Percentage + step label
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${(progress.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (progress.step == DaemonStartStep.Failed) RoseAccent
                                else CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        progress.step.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    @Composable
    private fun DaemonErrorScreen(
        onRetry: () -> Unit,
        progress: DaemonStartProgress
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(SurfaceBase),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    "⚠️",
                    fontSize = 48.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Could not connect",
                    style = MaterialTheme.typography.headlineSmall,
                    color = RoseAccent,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The agent daemon didn't start in time.\n" +
                    "This can happen on first install or after updates.",
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Retry", color = DeepCharcoal, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
