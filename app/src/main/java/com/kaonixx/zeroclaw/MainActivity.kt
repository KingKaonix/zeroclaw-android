package com.kaonixx.zeroclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.theme.*
import com.kaonixx.zeroclaw.ui.AppShell
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

    private val NOTIF_PERM_CODE = 100

    private sealed class UiState {
        object Loading : UiState()
        object DaemonError : UiState()
        data class Ready(val isPaired: Boolean) : UiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()
        startService(Intent(this, ZeroClawService::class.java))

        setContent {
            ZeroClawTheme {
                var state by remember { mutableStateOf<UiState>(UiState.Loading) }
                var retryTrigger by remember { mutableStateOf(0) }

                LaunchedEffect(retryTrigger) {
                    state = UiState.Loading
                    val ready = pollDaemon()
                    if (ready) {
                        try {
                            val status = ApiClient.getStatus()
                            state = UiState.Ready(isPaired = status.paired)
                        } catch (_: Exception) {
                            state = UiState.DaemonError
                        }
                    } else {
                        state = UiState.DaemonError
                    }
                }

                when (val s = state) {
                    is UiState.Loading -> LoadingScreen()
                    is UiState.DaemonError -> DaemonErrorScreen(onRetry = { retryTrigger++ })
                    is UiState.Ready -> AppShell(
                        context = this@MainActivity,
                        isPaired = s.isPaired,
                        onPair = { code ->
                            val response = ApiClient.pair(code)
                            if (response.success) {
                                ApiClient.setToken(response.token)
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

    private suspend fun pollDaemon(): Boolean {
        repeat(20) {
            delay(1500)
            try {
                ApiClient.getStatus()
                return true
            } catch (_: Exception) {
                // daemon not ready yet
            }
        }
        return false
    }

    // Back navigation handled by NavController via OnBackPressedDispatcher

    @Composable
    private fun LoadingScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = CyanAccent,
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Starting SimonAI\u2026",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    @Composable
    private fun DaemonErrorScreen(onRetry: () -> Unit) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    "Could not connect",
                    style = MaterialTheme.typography.headlineSmall,
                    color = RoseAccent,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The agent service did not start in time.\n" +
                    "Check device logs or restart the app.",
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("Retry", color = DeepCharcoal)
                }
            }
        }
    }
}
