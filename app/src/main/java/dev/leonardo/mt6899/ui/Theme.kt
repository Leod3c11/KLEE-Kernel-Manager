package dev.leonardo.mt6899.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1B33),
    secondary = Color(0xFF7DE3C8),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B222C),
    onSurfaceVariant = Color(0xFF9DA7B3),
    error = Color(0xFFFF6B6B)
)

val Amber = Color(0xFFFFB74D)
val Red = Color(0xFFFF6B6B)
val Green = Color(0xFF7DE3C8)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}

fun mhz(kHz: Long): String =
    if (kHz >= 1_000_000L) String.format(Locale.US, "%.2f GHz", kHz / 1_000_000.0) else "${kHz / 1000} MHz"

fun hzFmt(hz: Long): String =
    if (hz >= 1_000_000_000L) String.format(Locale.US, "%.2f GB/s", hz / 1e9) else "${hz / 1_000_000} MHz"

fun deg(c: Float): String = String.format(Locale.US, "%.1f°C", c)

fun tempColor(c: Float): Color = when {
    c >= 45f -> Red
    c >= 40f -> Amber
    else -> Color.Unspecified
}
