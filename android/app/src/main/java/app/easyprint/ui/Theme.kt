package app.easyprint.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.easyprint.android.LookPrefs
import app.easyprint.android.ThemeMode

/** Extra colours beyond Material's scheme. */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val paper: Color,
    val highContrast: Boolean = false,
)

val LightPalette = Palette(
    bg = Color(0xFFF3F1EC), surface = Color.White, surface2 = Color(0xFFF8F6F2), surface3 = Color(0xFFEFECE6),
    border = Color(0xFFE2DDD4), borderStrong = Color(0xFFCBC4B8), text = Color(0xFF1C1A17), muted = Color(0xFF645E55),
    accent = Color(0xFF2B55D6), onAccent = Color.White, accentSoft = Color(0xFFE9EEFC), accentLine = Color(0xFFB8C7F4),
    ok = Color(0xFF1D7F46), warn = Color(0xFFA35F00), err = Color(0xFFC0262D), paper = Color.White,
)

val DarkPalette = Palette(
    bg = Color(0xFF131417), surface = Color(0xFF1C1E23), surface2 = Color(0xFF22252B), surface3 = Color(0xFF2A2D35),
    border = Color(0xFF32363F), borderStrong = Color(0xFF464B57), text = Color(0xFFECEEF2), muted = Color(0xFFA6ACB8),
    accent = Color(0xFF7F9CFF), onAccent = Color(0xFF0C1330), accentSoft = Color(0xFF232C48), accentLine = Color(0xFF3F4F86),
    ok = Color(0xFF4CC27F), warn = Color(0xFFF0A843), err = Color(0xFFFF7B7F), paper = Color(0xFFFBFBF9),
)

val ContrastPalette = Palette(
    bg = Color.Black, surface = Color.Black, surface2 = Color.Black, surface3 = Color(0xFF111111),
    border = Color.White, borderStrong = Color.White, text = Color.White, muted = Color(0xFFF0F0F0),
    accent = Color(0xFFFFE14D), onAccent = Color.Black, accentSoft = Color.Black, accentLine = Color(0xFFFFE14D),
    ok = Color(0xFF6DFF9A), warn = Color(0xFFFFE14D), err = Color(0xFFFF8A8A), paper = Color.White, highContrast = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalReduceMotion = staticCompositionLocalOf { false }

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp),
)

@Composable
fun EasyPrintTheme(look: LookPrefs, content: @Composable () -> Unit) {
    val dark = when (look.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.CONTRAST -> true
    }
    val p = when {
        look.theme == ThemeMode.CONTRAST -> ContrastPalette
        dark -> DarkPalette
        else -> LightPalette
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.text,
        secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.accentSoft, onSecondaryContainer = p.text,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.muted, surfaceContainer = p.surface,
        surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface3,
        outline = p.borderStrong, outlineVariant = p.border, error = p.err, onError = Color.White,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accentLine,
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalReduceMotion provides look.reduceMotion,
        LocalDensity provides Density(density.density, density.fontScale * look.textScale),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
