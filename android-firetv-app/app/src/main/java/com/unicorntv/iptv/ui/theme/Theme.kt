package com.unicorntv.iptv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Cosmic Nebula Palette extracted from user artwork
val VoidIndigo = Color(0xFF0D061F)
val DeepSpacePurple = Color(0xFF1A0B36)
val CosmicMagenta = Color(0xFFE91E8C)
val NeonPink = Color(0xFFFF2E93)
val ElectricCyan = Color(0xFF00D2FF)
val RadiantViolet = Color(0xFF7B2CBF)
val StarlightWhite = Color(0xFFF8F9FF)

val NebulaBackgroundBrush = Brush.radialGradient(
    colors = listOf(
        Color(0xFF2B0E4D),
        DeepSpacePurple,
        VoidIndigo
    )
)

val AccentGradientBrush = Brush.horizontalGradient(
    colors = listOf(ElectricCyan, CosmicMagenta, NeonPink)
)

private val UnicornColorScheme = darkColorScheme(
    primary = CosmicMagenta,
    secondary = ElectricCyan,
    tertiary = RadiantViolet,
    background = VoidIndigo,
    surface = DeepSpacePurple,
    onPrimary = StarlightWhite,
    onSecondary = VoidIndigo,
    onBackground = StarlightWhite,
    onSurface = StarlightWhite
)

@Composable
fun UnicornTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UnicornColorScheme,
        content = content
    )
}
