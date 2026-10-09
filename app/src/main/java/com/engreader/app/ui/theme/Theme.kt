package com.engreader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = Palette.Pine,
    onPrimary = Palette.PaperRaised,
    primaryContainer = Palette.PineSoft,
    onPrimaryContainer = Palette.Pine,
    secondary = Palette.Amber,
    onSecondary = Palette.PaperRaised,
    secondaryContainer = Palette.AmberSoft,
    onSecondaryContainer = Color_OnAmberSoft,
    tertiary = Palette.PineBright,
    onTertiary = Palette.PaperRaised,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.PaperSunken,
    onSurfaceVariant = Palette.InkMuted,
    surfaceContainerLow = Palette.Paper,
    surfaceContainer = Palette.PaperSunken,
    surfaceContainerHigh = Palette.PaperSunken,
    outline = Palette.Rule,
    outlineVariant = Palette.Rule,
    error = Palette.Clay,
    onError = Palette.PaperRaised,
    errorContainer = Palette.ClaySoft,
    onErrorContainer = Palette.Clay,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.Mint,
    onPrimary = Color_OnMint,
    primaryContainer = Palette.MintSoft,
    onPrimaryContainer = Palette.Mint,
    secondary = Palette.AmberLight,
    onSecondary = Color_OnAmber,
    secondaryContainer = Palette.AmberLightSoft,
    onSecondaryContainer = Palette.AmberLight,
    tertiary = Palette.Mint,
    onTertiary = Color_OnMint,
    background = Palette.NightBase,
    onBackground = Palette.NightInk,
    surface = Palette.NightBase,
    onSurface = Palette.NightInk,
    surfaceVariant = Palette.NightSunken,
    onSurfaceVariant = Palette.NightInkMuted,
    surfaceContainerLow = Palette.NightBase,
    surfaceContainer = Palette.NightRaised,
    surfaceContainerHigh = Palette.NightSunken,
    outline = Palette.NightRule,
    outlineVariant = Palette.NightRule,
    error = Palette.AmberLight,
    onError = Color_OnAmber,
    errorContainer = Color_OnErrorContainerDark,
    onErrorContainer = Palette.AmberLight,
)

@Composable
fun EngReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
