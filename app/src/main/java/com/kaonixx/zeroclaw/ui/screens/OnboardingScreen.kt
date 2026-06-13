package com.kaonixx.zeroclaw.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaonixx.zeroclaw.api.ApiClient
import com.kaonixx.zeroclaw.model.QuickstartApplyRequest
import com.kaonixx.zeroclaw.theme.*
import kotlinx.coroutines.launch

/** Provider types the daemon supports */
private data class ProviderOption(
    val type: String,
    val label: String,
    val icon: String,
    val needsApiKey: Boolean = true,
    val models: List<String> = emptyList()
)

private val knownProviders = listOf(
    ProviderOption("openai", "OpenAI", "O", models = listOf(
        "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo",
        "o1", "o1-mini", "o3-mini"
    )),
    ProviderOption("anthropic", "Anthropic", "A", models = listOf(
        "claude-sonnet-4-20250514", "claude-sonnet-4", "claude-haiku-3-5",
        "claude-opus-4", "claude-3-5-sonnet-latest", "claude-3-haiku"
    )),
    ProviderOption("openrouter", "OpenRouter", "R", models = listOf(
        "openrouter/auto", "anthropic/claude-sonnet-4", "openai/gpt-4o",
        "google/gemini-2.0-flash-001", "meta-llama/llama-4", "deepseek/deepseek-chat"
    )),
    ProviderOption("google", "Google Gemini", "G", models = listOf(
        "gemini-2.5-pro", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"
    )),
    ProviderOption("ollama", "Ollama (Local)", "L", needsApiKey = false, models = listOf(
        "llama3.2", "llama3.1", "mistral", "codellama", "phi4", "qwen2.5"
    )),
    ProviderOption("custom", "Custom OpenAI", "C", needsApiKey = true, models = listOf(
        "custom"
    ))
)

private val customModels = listOf(
    "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo",
    "o1", "o1-mini", "o3-mini",
    "claude-sonnet-4-20250514", "claude-haiku-3-5",
    "gemini-2.0-flash", "gemini-1.5-pro",
    "deepseek-chat", "deepseek-reasoner",
    "mistral-large", "mixtral-8x7b",
    "llama-3.3-70b", "llama-3.1-8b",
    "qwen2.5-72b", "qwen2.5-32b",
    "command-r-plus", "command-r",
    "custom"
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var selectedProvider by remember { mutableStateOf<ProviderOption?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var agentName by remember { mutableStateOf("default") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val totalSteps = 3 // Provider → Model → API Key

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBase)
    ) {
        // Nebula background
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .size(350.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 80.dp, y = (-80).dp)
                    .background(PurpleAccent.copy(alpha = 0.08f), RoundedCornerShape(100.dp))
                    .blur(120.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(40.dp))

            // Header
            Text(
                "Set up your AI",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect a model provider to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )

            Spacer(Modifier.height(24.dp))

            // Step indicator
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                (0 until totalSteps).forEach { i ->
                    val isActive = i == step
                    val isDone = i < step
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isDone) CyanAccent
                                else if (isActive) CyanAccent.copy(alpha = 0.5f)
                                else SurfaceElevated
                            )
                    )
                    if (i < totalSteps - 1) Spacer(Modifier.width(4.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Provider", style = MaterialTheme.typography.labelSmall,
                    color = if (step >= 0) CyanAccent else TextMuted)
                Text("Model", style = MaterialTheme.typography.labelSmall,
                    color = if (step >= 1) CyanAccent else TextMuted)
                Text("Key", style = MaterialTheme.typography.labelSmall,
                    color = if (step >= 2) CyanAccent else TextMuted)
            }

            Spacer(Modifier.height(32.dp))

            // === STEP 0: Provider Selection ===
            if (step == 0) {
                Text(
                    "Choose Provider",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))

                knownProviders.forEach { provider ->
                    val isSelected = selectedProvider?.type == provider.type
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedProvider = provider
                                selectedModel = ""
                                if (provider.type == "custom") customUrl = ""
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) CyanAccent.copy(alpha = 0.1f) else SurfaceCard,
                        border = if (isSelected) {
                            androidx.compose.foundation.BorderStroke(
                                1.dp, CyanAccent.copy(alpha = 0.3f)
                            )
                        } else null,
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Icon
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) CyanAccent.copy(alpha = 0.2f)
                                        else SurfaceElevated
                            ) {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        provider.icon,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) CyanAccent else TextMuted,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    provider.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.SemiBold
                                                    else FontWeight.Normal
                                )
                                if (!provider.needsApiKey) {
                                    Text(
                                        "No API key required",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GreenAccent
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    "Selected",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }

            // === STEP 1: Model Selection ===
            if (step == 1) {
                Text(
                    "Select Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                if (selectedProvider?.type == "custom") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Custom OpenAI-compatible endpoint",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://your-endpoint.com/v1", color = TextMuted) },
                        label = { Text("API Base URL", color = TextMuted) },
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
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                val models = if (selectedProvider?.type == "custom") customModels
                             else selectedProvider?.models ?: emptyList()

                // Model picker as selectable cards
                models.forEach { model ->
                    val isSelected = selectedModel == model
                    val isCustomOption = model == "custom"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                selectedModel = model
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) CyanAccent.copy(alpha = 0.1f) else SurfaceCard,
                        border = if (isSelected) {
                            androidx.compose.foundation.BorderStroke(
                                1.dp, CyanAccent.copy(alpha = 0.3f)
                            )
                        } else null,
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCustomOption) {
                                Icon(
                                    Icons.Default.Add,
                                    null,
                                    tint = if (isSelected) CyanAccent else TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (isCustomOption) "Custom model name" else model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.SemiBold
                                                    else FontWeight.Normal
                                )
                                if (isCustomOption) {
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = if (selectedModel == "custom") {
                                            // Extract custom name if already set
                                            if (selectedModel.startsWith("custom:"))
                                                selectedModel.removePrefix("custom:")
                                            else ""
                                        } else "",
                                        onValueChange = {
                                            selectedModel = "custom:$it"
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("e.g. gpt-4o-custom", color = TextMuted) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = BorderDim,
                                            cursorColor = CyanAccent,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            focusedContainerColor = SurfaceElevated,
                                            unfocusedContainerColor = SurfaceElevated
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // === STEP 2: API Key ===
            if (step == 2) {
                Text(
                    "API Key",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                if (selectedProvider?.needsApiKey == true) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your key is stored locally and never sent anywhere but the provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("sk-...", color = TextMuted) },
                        label = { Text("${selectedProvider?.label ?: "API"} Key", color = TextMuted) },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None
                                                else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    null,
                                    tint = TextMuted
                                )
                            }
                        },
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = GreenAccent.copy(alpha = 0.1f)
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info, null,
                                tint = GreenAccent, modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Local provider — no API key needed",
                                style = MaterialTheme.typography.bodySmall,
                                color = GreenAccent
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = agentName,
                    onValueChange = { agentName = it.lowercase().replace(Regex("\\s+"), "-") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("default", color = TextMuted) },
                    label = { Text("Agent Name", color = TextMuted) },
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
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            // Navigation buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step--; error = null },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDim)
                    ) {
                        Icon(Icons.Default.ArrowBack, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Back")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                val canAdvance = when (step) {
                    0 -> selectedProvider != null
                    1 -> selectedModel.isNotBlank() &&
                         (selectedProvider?.type != "custom" || customUrl.isNotBlank() ||
                          selectedModel.startsWith("custom:"))
                    2 -> (selectedProvider?.needsApiKey != true || apiKey.isNotBlank()) &&
                         agentName.isNotBlank()
                    else -> false
                }

                Button(
                    onClick = {
                        if (step < totalSteps - 1) {
                            step++
                            error = null
                        } else {
                            // Submit
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    val modelValue = if (selectedModel.startsWith("custom:"))
                                        selectedModel.removePrefix("custom:")
                                    else selectedModel

                                    val req = QuickstartApplyRequest(
                                        modelProvider = selectedProvider?.type ?: "openai",
                                        model = modelValue,
                                        apiKey = if (selectedProvider?.needsApiKey == true)
                                                    apiKey.ifBlank { null } else null,
                                        agent = agentName
                                    )
                                    val resp = ApiClient.applyQuickstart(req)
                                    if (resp.success) {
                                        onComplete()
                                    } else {
                                        error = resp.message ?: "Setup failed"
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "Connection error"
                                }
                                loading = false
                            }
                        }
                    },
                    enabled = canAdvance && !loading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canAdvance) CyanAccent else SurfaceElevated
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = DeepCharcoal,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            if (step < totalSteps - 1) "Next" else "Finish Setup",
                            color = if (canAdvance) DeepCharcoal else TextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (step < totalSteps - 1) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Error
            error?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = RoseAccent.copy(alpha = 0.1f)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline, null,
                            tint = RoseAccent, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = RoseAccent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
