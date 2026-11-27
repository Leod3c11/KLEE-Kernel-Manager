package dev.klee.kernelmanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object KleeColors {
    val Background = Color(0xFF090C12)
    val Surface = Color(0xFF111622)
    val Card = Color(0xFF161C2B)
    val CardElevated = Color(0xFF1D2538)
    val Border = Color(0xFF263047)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)

    // Module & Semantic Accents
    val Primary = Color(0xFF38BDF8)
    val Cpu = Color(0xFF3B82F6)
    val Gpu = Color(0xFFA855F7)
    val Thermal = Color(0xFFF97316)
    val Power = Color(0xFF10B981)
    val Gaming = Color(0xFFF43F5E)
    val Monitor = Color(0xFF06B6D4)
    val Tuning = Color(0xFF6366F1)
    val Device = Color(0xFF14B8A6)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
}

private val KleeDarkScheme = darkColorScheme(
    primary = KleeColors.Primary,
    onPrimary = Color(0xFF041A27),
    primaryContainer = Color(0xFF0E3A53),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = KleeColors.Gpu,
    onSecondary = Color(0xFF1F0733),
    secondaryContainer = Color(0xFF3B1A5A),
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = KleeColors.Power,
    onTertiary = Color(0xFF022C22),
    background = KleeColors.Background,
    onBackground = KleeColors.TextPrimary,
    surface = KleeColors.Surface,
    onSurface = KleeColors.TextPrimary,
    surfaceVariant = KleeColors.Card,
    onSurfaceVariant = KleeColors.TextSecondary,
    outline = KleeColors.Border,
    error = KleeColors.Danger,
    onError = Color.White,
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFECACA)
)

private val KleeTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun KleeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KleeDarkScheme,
        typography = KleeTypography,
        content = content
    )
}
