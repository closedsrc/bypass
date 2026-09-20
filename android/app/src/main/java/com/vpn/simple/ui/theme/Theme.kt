package com.vpn.simple.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Colour tokens. Every value is a named role, never a raw hex at a call site,
// so light and dark stay in step and nothing is hard-coded per screen.
// ---------------------------------------------------------------------------

/** Roles the product needs that Material's scheme has no slot for. */
data class StatusColors(
    val connected: Color,
    val onConnectedContainer: Color,
    val connectedContainer: Color,
    val disconnected: Color,
    val disconnectedContainer: Color,
    val connecting: Color,
    val onConnectingContainer: Color,
    val connectingContainer: Color,
)

private val LightStatus = StatusColors(
    connected = Color(0xFF0B6B36),
    onConnectedContainer = Color(0xFF063D1E),
    connectedContainer = Color(0xFFD3F2E0),
    disconnected = Color(0xFF52525F),
    disconnectedContainer = Color(0xFFE7E6EE),
    connecting = Color(0xFF8A4B00),
    onConnectingContainer = Color(0xFF4A2800),
    connectingContainer = Color(0xFFFDEBD2),
)

private val DarkStatus = StatusColors(
    connected = Color(0xFF5BD98C),
    onConnectedContainer = Color(0xFFB9F3CE),
    connectedContainer = Color(0xFF12351F),
    disconnected = Color(0xFFA5A3B2),
    disconnectedContainer = Color(0xFF26262E),
    connecting = Color(0xFFF0B45E),
    onConnectingContainer = Color(0xFFFFE1B8),
    connectingContainer = Color(0xFF3A2A0C),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4A3FD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E0FF),
    onPrimaryContainer = Color(0xFF14095E),
    secondary = Color(0xFF5A5A6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E6EE),
    onSecondaryContainer = Color(0xFF1B1B23),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF14131A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14131A),
    surfaceVariant = Color(0xFFF1F0F7),
    onSurfaceVariant = Color(0xFF4A4757),
    outline = Color(0xFFD6D3E0),
    outlineVariant = Color(0xFFE6E4EE),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF601410),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB6B0FF),
    onPrimary = Color(0xFF211A6B),
    primaryContainer = Color(0xFF322A94),
    onPrimaryContainer = Color(0xFFE3E0FF),
    secondary = Color(0xFFA5A3B2),
    onSecondary = Color(0xFF26262E),
    secondaryContainer = Color(0xFF35343F),
    onSecondaryContainer = Color(0xFFE7E6EE),
    background = Color(0xFF0F0F13),
    onBackground = Color(0xFFECEBF1),
    surface = Color(0xFF17171D),
    onSurface = Color(0xFFECEBF1),
    surfaceVariant = Color(0xFF232330),
    onSurfaceVariant = Color(0xFFA8A5B6),
    outline = Color(0xFF3A3946),
    outlineVariant = Color(0xFF2A2933),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF4E1512),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color(0xFF000000),
)

// ---------------------------------------------------------------------------
// Type scale. One family, one ratio, named roles — not per-screen font sizes.
// ---------------------------------------------------------------------------

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

// ---------------------------------------------------------------------------
// Spacing and shape tokens. 4dp base step; radii from one family.
// ---------------------------------------------------------------------------

data class Spacing(
    val xs: androidx.compose.ui.unit.Dp = 4.dp,
    val sm: androidx.compose.ui.unit.Dp = 8.dp,
    val md: androidx.compose.ui.unit.Dp = 12.dp,
    val lg: androidx.compose.ui.unit.Dp = 16.dp,
    val xl: androidx.compose.ui.unit.Dp = 24.dp,
    val xxl: androidx.compose.ui.unit.Dp = 32.dp,
    val huge: androidx.compose.ui.unit.Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun BypassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val status = if (darkTheme) DarkStatus else LightStatus
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalStatusColors provides status,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
