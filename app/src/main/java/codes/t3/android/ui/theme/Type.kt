package codes.t3.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import codes.t3.android.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

private val base = Typography().let { t ->
    fun TextStyle.dm() = copy(fontFamily = DmSans)
    Typography(
        displayLarge = t.displayLarge.dm(), displayMedium = t.displayMedium.dm(), displaySmall = t.displaySmall.dm(),
        headlineLarge = t.headlineLarge.dm(), headlineMedium = t.headlineMedium.dm(), headlineSmall = t.headlineSmall.dm(),
        titleLarge = t.titleLarge.dm(), titleMedium = t.titleMedium.dm(), titleSmall = t.titleSmall.dm(),
        bodyLarge = t.bodyLarge.dm(), bodyMedium = t.bodyMedium.dm(), bodySmall = t.bodySmall.dm(),
        labelLarge = t.labelLarge.dm(), labelMedium = t.labelMedium.dm(), labelSmall = t.labelSmall.dm(),
    )
}

/** Material 3 type scale with tightened, punchier headings for an expressive feel. */
val T3Typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
