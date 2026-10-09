package codes.t3.android.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A small monogram disc in the provider's brand color. */
@Composable
fun ProviderGlyph(driver: String, modifier: Modifier = Modifier) {
    val (letter, color) = when (driver) {
        "claudeAgent", "claude" -> "C" to Color(0xFFD97757)
        "codex" -> "O" to Color(0xFF10A37F)
        "cursor" -> "Cu" to Color(0xFF6E6E73)
        "grok" -> "G" to Color(0xFF1D1D1F)
        "opencode" -> "Oc" to Color(0xFF3B82F6)
        "antigravity" -> "A" to Color(0xFF4285F4)
        "" -> "?" to MaterialTheme.colorScheme.outline
        else -> driver.take(1).uppercase() to MaterialTheme.colorScheme.secondary
    }
    Surface(shape = CircleShape, color = color, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
