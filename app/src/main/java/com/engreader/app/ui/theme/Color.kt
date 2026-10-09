package com.engreader.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Warm paper palette. Reading is a long-session task, so the base surfaces are
 * low-glare off-white and the accents stay muted except where the user must act.
 */
object Palette {
    val Paper = Color(0xFFFAF8F3)
    val PaperRaised = Color(0xFFFFFFFF)
    val PaperSunken = Color(0xFFF0EBE1)
    val Ink = Color(0xFF1C1B18)
    val InkMuted = Color(0xFF6B6560)
    val Rule = Color(0xFFDCD5C7)

    val Pine = Color(0xFF1F5A4A)
    val PineSoft = Color(0xFFE3EEE9)
    val PineBright = Color(0xFF2E7A64)
    val Amber = Color(0xFFB45309)
    val AmberSoft = Color(0xFFFBEBD3)
    val Clay = Color(0xFFB3261E)
    val ClaySoft = Color(0xFFFBE6E4)

    val NightBase = Color(0xFF16181A)
    val NightRaised = Color(0xFF1F2225)
    val NightSunken = Color(0xFF262A2E)
    val NightInk = Color(0xFFE7E4DC)
    val NightInkMuted = Color(0xFF9BA0A6)
    val NightRule = Color(0xFF343A40)
    val Mint = Color(0xFF7FC8B0)
    val MintSoft = Color(0xFF1D332C)
    val AmberLight = Color(0xFFE0A458)
    val AmberLightSoft = Color(0xFF33280F)
}

/** The three reading surfaces a user can pick inside the reader, independent of app theme. */
enum class ReadingTheme(val label: String) {
    Paper("纸白"),
    Sepia("米黄"),
    Night("夜间");

    val background: Color
        get() = when (this) {
            Paper -> Color(0xFFFAF8F3)
            Sepia -> Color(0xFFF4E8D2)
            Night -> Color(0xFF16181A)
        }

    val body: Color
        get() = when (this) {
            Paper -> Color(0xFF1C1B18)
            Sepia -> Color(0xFF3A3227)
            Night -> Color(0xFFC9CBCE)
        }

    val subtle: Color
        get() = when (this) {
            Paper -> Color(0xFF6B6560)
            Sepia -> Color(0xFF7A6B54)
            Night -> Color(0xFF82878D)
        }

    val isDark: Boolean get() = this == Night
}
