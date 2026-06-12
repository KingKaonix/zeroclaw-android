package com.kaonixx.zeroclaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.theme.*
import kotlinx.coroutines.launch

@Composable
fun PairingScreen(onPair: suspend (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var displayCode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var codeLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val resp = ApiClient.getPairingCode()
            resp.pairingCode?.let {
                displayCode = it
                code = it
            }
        } catch (_: Exception) { }
        codeLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo icon
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(16.dp),
                color = CyanAccentGlow,
                border = null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("SimonAI", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))

            Text(
                if (displayCode != null) "Your pairing code \u2014 ready to connect"
                else "Enter the 6-digit code from your terminal",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )

            Spacer(Modifier.height(24.dp))

            // Display code card
            AnimatedVisibility(visible = !codeLoading && displayCode != null, enter = fadeIn()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CyanAccentGlow,
                    border = null
                ) {
                    Text(
                        text = displayCode ?: "",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 12.sp
                        ),
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Code input
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) { code = it; error = null } },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    letterSpacing = 8.sp
                ),
                placeholder = { Text("000000", textAlign = TextAlign.Center) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = BorderDim,
                    cursorColor = CyanAccent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                ),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (code.length >= 6) {
                            loading = true
                            scope.launch {
                                try {
                                    onPair(code)
                                    loading = false
                                } catch (e: Exception) {
                                    error = e.message ?: "Pairing failed"
                                    loading = false
                                }
                            }
                        }
                    }
                )
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = RoseAccent, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))

            // Pair button
            Button(
                onClick = {
                    loading = true
                    scope.launch {
                        try { onPair(code); loading = false }
                        catch (e: Exception) { error = e.message ?: "Pairing failed"; loading = false }
                    }
                },
                enabled = !loading && code.length >= 6,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (code.length >= 6) CyanAccent else SurfaceElevated,
                    disabledContainerColor = SurfaceElevated
                )
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Pair", color = if (code.length >= 6) DeepCharcoal else TextMuted)
                }
            }
        }
    }
}
