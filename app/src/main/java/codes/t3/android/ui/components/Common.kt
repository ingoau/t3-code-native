package codes.t3.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.t3.android.R

/** "T3 Code" header lockup: wordmark + "Code" + stage pill. */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, stage: String? = "Alpha") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.t3_wordmark),
            contentDescription = "T3",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(17.dp).size(width = 28.dp, height = 17.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Code",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stage != null) {
            Spacer(Modifier.size(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    stage.uppercase(),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** Centered illustration + title + body + optional action, with an expressive shape behind the icon. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            if (loading) {
                ContainedLoadingIndicator(Modifier.size(72.dp))
            } else {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .graphicsLayer { },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Text with a moving highlight, used for live "Thinking" / "Running …" labels. */
@Composable
fun ShimmerText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "x",
    )
    val brush = Brush.linearGradient(
        colors = listOf(color.copy(alpha = 0.45f), color, color.copy(alpha = 0.45f)),
        start = androidx.compose.ui.geometry.Offset(x * 400f - 200f, 0f),
        end = androidx.compose.ui.geometry.Offset(x * 400f + 200f, 0f),
    )
    Text(text, style = style.copy(brush = brush), modifier = modifier, maxLines = 1)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

val PillShape = RoundedCornerShape(percent = 50)

