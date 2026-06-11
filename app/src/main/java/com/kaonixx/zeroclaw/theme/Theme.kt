package com.kaonixx.zeroclaw.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = DeepCharcoal,
    primaryContainer = CyanAccentAlpha,
    onPrimaryContainer = CyanAccent,
    secondary = PurpleAccent,
    onSecondary = DeepCharcoal,
    secondaryContainer = PurpleAccentGlow,
    onSecondaryContainer = PurpleAccent,
    tertiary = GreenAccent,
    onTertiary = DeepCharcoal,
    error = RoseAccent,
    onError = DeepCharcoal,
    errorContainer = RoseGlow,
    onErrorContainer = RoseAccent,
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = DeepCharcoal,
    onSurface = TextPrimary,
    surfaceVariant = DeepCharcoalElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderDim,
    outlineVariant = BorderAccent
)

@Composable
fun ZeroClawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
