package codes.t3.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode { System, Light, Dark }

/** "T3 Code" brand palette: T3 blue on near-neutral surfaces (mirrors the upstream default theme). */
private val BrandLight = lightColorScheme(
    primary = Color(0xFF1B4ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174D),
    secondary = Color(0xFF52596F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E6EE),
    onSecondaryContainer = Color(0xFF1A1C24),
    tertiary = Color(0xFF6B4FA8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF250059),
    error = Color(0xFFC10007),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCEBEC),
    onErrorContainer = Color(0xFF5C0003),
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF27272A),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF27272A),
    surfaceVariant = Color(0xFFEFEFF1),
    onSurfaceVariant = Color(0xFF6F6F79),
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE4E4E7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEDEDEF),
    surfaceContainerHighest = Color(0xFFE6E6E9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE2E2E5),
    inverseSurface = Color(0xFF27272A),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFF8FAEFF),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFF7FA0FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF346BF1),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFC3C6D4),
    onSecondary = Color(0xFF2C303D),
    secondaryContainer = Color(0xFF2A2B30),
    onSecondaryContainer = Color(0xFFE2E3EA),
    tertiary = Color(0xFFCDB8FF),
    onTertiary = Color(0xFF3B1E72),
    tertiaryContainer = Color(0xFF52398A),
    onTertiaryContainer = Color(0xFFEADDFF),
    error = Color(0xFFFF6467),
    onError = Color(0xFF3D0004),
    errorContainer = Color(0xFF301214),
    onErrorContainer = Color(0xFFFFB3B3),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1F1F22),
    onSurfaceVariant = Color(0xFF9A9AA3),
    outline = Color(0xFF5A5A62),
    outlineVariant = Color(0xFF26262A),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF161617),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerHighest = Color(0xFF242427),
    surfaceBright = Color(0xFF2A2A2D),
    surfaceDim = Color(0xFF0A0A0A),
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF27272A),
    inversePrimary = Color(0xFF1B4ED8),
)

/** Extra semantic colors that Material's scheme doesn't model: status dots, diff colors, etc. */
@Immutable
data class T3Colors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    val codeBackground: Color,
)

private val LightExtra = T3Colors(
    success = Color(0xFF1E7B3A),
    successContainer = Color(0xFFC5F0CC),
    onSuccessContainer = Color(0xFF00210B),
    warning = Color(0xFF8A5100),
    warningContainer = Color(0xFFFFDDB6),
    onWarningContainer = Color(0xFF2C1600),
    info = Color(0xFF00639B),
    diffAdded = Color(0xFF1E7B3A),
    diffRemoved = Color(0xFFBA1A1A),
    codeBackground = Color(0xFFF1ECF4),
)

private val DarkExtra = T3Colors(
    success = Color(0xFF7FDA93),
    successContainer = Color(0xFF00531F),
    onSuccessContainer = Color(0xFFC5F0CC),
    warning = Color(0xFFFFB95C),
    warningContainer = Color(0xFF693C00),
    onWarningContainer = Color(0xFFFFDDB6),
    info = Color(0xFF93CCFF),
    diffAdded = Color(0xFF7FDA93),
    diffRemoved = Color(0xFFFFB4AB),
    codeBackground = Color(0xFF1A181D),
)

val LocalT3Colors = staticCompositionLocalOf { DarkExtra }

/** Expressive shape scale: generous rounding everywhere. */
val T3Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun T3Theme(
    mode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    var scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    if (dark && pureBlack) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalT3Colors provides if (dark) DarkExtra else LightExtra) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = T3Shapes,
            typography = T3Typography,
            content = content,
        )
    }
}

object T3 {
    val colors: T3Colors
        @Composable get() = LocalT3Colors.current
}

