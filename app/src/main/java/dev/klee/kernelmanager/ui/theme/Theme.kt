package dev.klee.kernelmanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class KleePalette(
    val background: Color,
    val surface: Color,
    val card: Color,
    val cardElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primary: Color,
    val secondary: Color,
    val heroStart: Color,
    val heroMid: Color,
    val heroEnd: Color
)

enum class KleeThemePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val palette: KleePalette
) {
    OBSIDIAN_CYBER(
        id = "OBSIDIAN_CYBER",
        title = "Obsidian Cyber",
        subtitle = "Azul profundo + Cyan elétrico",
        palette = KleePalette(
            background = Color(0xFF090C12),
            surface = Color(0xFF111622),
            card = Color(0xFF161C2B),
            cardElevated = Color(0xFF1D2538),
            border = Color(0xFF263047),
            textPrimary = Color(0xFFF1F5F9),
            textSecondary = Color(0xFF94A3B8),
            textMuted = Color(0xFF64748B),
            primary = Color(0xFF38BDF8),
            secondary = Color(0xFFA855F7),
            heroStart = Color(0xFF141E33),
            heroMid = Color(0xFF17182B),
            heroEnd = Color(0xFF111827)
        )
    ),
    AMOLED_BLACK(
        id = "AMOLED_BLACK",
        title = "AMOLED Pitch Black",
        subtitle = "Preto puro #000 + Turquesa neon",
        palette = KleePalette(
            background = Color(0xFF000000),
            surface = Color(0xFF080A0E),
            card = Color(0xFF0E1217),
            cardElevated = Color(0xFF161B22),
            border = Color(0xFF212832),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF9CA3AF),
            textMuted = Color(0xFF6B7280),
            primary = Color(0xFF00F5D4),
            secondary = Color(0xFF38BDF8),
            heroStart = Color(0xFF071A1D),
            heroMid = Color(0xFF060E14),
            heroEnd = Color(0xFF05070A)
        )
    ),
    CRIMSON_ROG(
        id = "CRIMSON_ROG",
        title = "Crimson Gaming",
        subtitle = "Carbono escuro + Vermelho neon",
        palette = KleePalette(
            background = Color(0xFF0B080A),
            surface = Color(0xFF140F12),
            card = Color(0xFF1C1419),
            cardElevated = Color(0xFF271B22),
            border = Color(0xFF3A2430),
            textPrimary = Color(0xFFFFF1F2),
            textSecondary = Color(0xFFBFA6AE),
            textMuted = Color(0xFF7D636D),
            primary = Color(0xFFFF2E55),
            secondary = Color(0xFFFF7A00),
            heroStart = Color(0xFF2B0F1A),
            heroMid = Color(0xFF1F1016),
            heroEnd = Color(0xFF140B0E)
        )
    ),
    EMERALD_MATRIX(
        id = "EMERALD_MATRIX",
        title = "Emerald Matrix",
        subtitle = "Verde abissal + Esmeralda vivo",
        palette = KleePalette(
            background = Color(0xFF060E0B),
            surface = Color(0xFF0C1713),
            card = Color(0xFF11211B),
            cardElevated = Color(0xFF172D25),
            border = Color(0xFF224236),
            textPrimary = Color(0xFFECFDF5),
            textSecondary = Color(0xFF8FB8A8),
            textMuted = Color(0xFF5B8273),
            primary = Color(0xFF10B981),
            secondary = Color(0xFF84CC16),
            heroStart = Color(0xFF0C2A20),
            heroMid = Color(0xFF0A1D17),
            heroEnd = Color(0xFF07130F)
        )
    ),
    ROYAL_HYPEROS(
        id = "ROYAL_HYPEROS",
        title = "Hyper Violet",
        subtitle = "Roxo espacial + Violeta vibrante",
        palette = KleePalette(
            background = Color(0xFF0B0814),
            surface = Color(0xFF130F21),
            card = Color(0xFF1B152E),
            cardElevated = Color(0xFF241C3D),
            border = Color(0xFF362A59),
            textPrimary = Color(0xFFF5F3FF),
            textSecondary = Color(0xFFB3A6D6),
            textMuted = Color(0xFF786B9C),
            primary = Color(0xFFC084FC),
            secondary = Color(0xFFF472B6),
            heroStart = Color(0xFF261547),
            heroMid = Color(0xFF1B1233),
            heroEnd = Color(0xFF120C21)
        )
    );

    companion object {
        fun fromId(id: String?): KleeThemePreset =
            entries.firstOrNull { it.id == id } ?: OBSIDIAN_CYBER
    }
}

object KleeColors {
    private var activePalette by mutableStateOf(KleeThemePreset.OBSIDIAN_CYBER.palette)

    fun applyPreset(preset: KleeThemePreset) {
        activePalette = preset.palette
    }

    val Background: Color get() = activePalette.background
    val Surface: Color get() = activePalette.surface
    val Card: Color get() = activePalette.card
    val CardElevated: Color get() = activePalette.cardElevated
    val Border: Color get() = activePalette.border
    val TextPrimary: Color get() = activePalette.textPrimary
    val TextSecondary: Color get() = activePalette.textSecondary
    val TextMuted: Color get() = activePalette.textMuted
    val Primary: Color get() = activePalette.primary
    val HeroStart: Color get() = activePalette.heroStart
    val HeroMid: Color get() = activePalette.heroMid
    val HeroEnd: Color get() = activePalette.heroEnd

    // Module & Semantic Accents
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
fun KleeTheme(
    preset: KleeThemePreset = KleeThemePreset.OBSIDIAN_CYBER,
    content: @Composable () -> Unit
) {
    KleeColors.applyPreset(preset)
    val p = preset.palette
    val colorScheme = darkColorScheme(
        primary = p.primary,
        onPrimary = Color(0xFF04121B),
        primaryContainer = p.primary.copy(alpha = 0.22f),
        onPrimaryContainer = p.textPrimary,
        secondary = p.secondary,
        onSecondary = Color(0xFF150524),
        secondaryContainer = p.secondary.copy(alpha = 0.22f),
        onSecondaryContainer = p.textPrimary,
        tertiary = KleeColors.Power,
        onTertiary = Color(0xFF022C22),
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.card,
        onSurfaceVariant = p.textSecondary,
        outline = p.border,
        error = KleeColors.Danger,
        onError = Color.White,
        errorContainer = Color(0xFF450A0A),
        onErrorContainer = Color(0xFFFECACA)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KleeTypography,
        content = content
    )
}
