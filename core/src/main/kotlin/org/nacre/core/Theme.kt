package org.nacre.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Ink = Color(0xFF0B0B0C)
    val Graphite = Color(0xFF1C1C1F)
    val Ash = Color(0xFF8E8E93)
    val Mist = Color(0xFFE6E6E3)
    val Paper = Color(0xFFFAFAF7)
    val Silver = Color(0xFFC7C7CC)
}

private val LightColors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Palette.Paper,
    secondary = Palette.Graphite,
    onSecondary = Palette.Paper,
    tertiary = Palette.Silver,
    onTertiary = Palette.Ink,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Mist,
    onSurfaceVariant = Palette.Graphite,
    outline = Palette.Ash,
    outlineVariant = Palette.Silver,
    primaryContainer = Palette.Ink,
    onPrimaryContainer = Palette.Paper,
    secondaryContainer = Palette.Silver,
    onSecondaryContainer = Palette.Ink,
    tertiaryContainer = Palette.Silver,
    onTertiaryContainer = Palette.Ink,
    inverseSurface = Palette.Graphite,
    inverseOnSurface = Palette.Paper,
    inversePrimary = Palette.Mist,
    surfaceTint = Palette.Ink,
    surfaceContainerLowest = Palette.Paper,
    surfaceContainerLow = Palette.Paper,
    surfaceContainer = Palette.Mist,
    surfaceContainerHigh = Palette.Mist,
    surfaceContainerHighest = Palette.Mist,
)

private val DarkColors = darkColorScheme(
    primary = Palette.Paper,
    onPrimary = Palette.Ink,
    secondary = Palette.Mist,
    onSecondary = Palette.Ink,
    tertiary = Palette.Silver,
    onTertiary = Palette.Ink,
    background = Palette.Ink,
    onBackground = Palette.Paper,
    surface = Palette.Ink,
    onSurface = Palette.Paper,
    surfaceVariant = Palette.Graphite,
    onSurfaceVariant = Palette.Mist,
    outline = Palette.Ash,
    outlineVariant = Palette.Graphite,
    primaryContainer = Palette.Paper,
    onPrimaryContainer = Palette.Ink,
    secondaryContainer = Palette.Ash,
    onSecondaryContainer = Palette.Ink,
    tertiaryContainer = Palette.Graphite,
    onTertiaryContainer = Palette.Paper,
    inverseSurface = Palette.Mist,
    inverseOnSurface = Palette.Ink,
    inversePrimary = Palette.Graphite,
    surfaceTint = Palette.Paper,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Ink,
    surfaceContainer = Palette.Graphite,
    surfaceContainerHigh = Palette.Graphite,
    surfaceContainerHighest = Palette.Graphite,
)

@Composable
fun NacreTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
