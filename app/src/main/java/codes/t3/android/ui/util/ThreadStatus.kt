package codes.t3.android.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LiveHelp
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.ui.theme.T3

/** What a thread row should call out, in priority order (mirrors the upstream inbox labels). */
enum class ThreadBadge(val label: String, val icon: ImageVector) {
    Approval("Approval", Icons.Rounded.Shield),
    Input("Input", Icons.Rounded.LiveHelp),
    Plan("Plan ready", Icons.Rounded.Lightbulb),
    Working("Working", Icons.Rounded.HourglassTop),
    Failed("Failed", Icons.Rounded.ErrorOutline),
    Limited("Limited", Icons.Rounded.Speed),
    Done("Done", Icons.Rounded.CheckCircle),
}

fun ThreadShell.badge(): ThreadBadge? {
    pendingRuntimeRequest?.let { return if (it.kind == "user_input") ThreadBadge.Input else ThreadBadge.Approval }
    if (isWorking) return ThreadBadge.Working
    if (status == "failed") return if (lastErrorClass == "usage_limit") ThreadBadge.Limited else ThreadBadge.Failed
    if (hasActionableProposedPlan) return ThreadBadge.Plan
    val completed = parseInstant(latestRunCompletedAt)
    val visited = parseInstant(lastVisitedAt)
    if (status == "completed" && completed != null && (visited == null || completed.isAfter(visited))) return ThreadBadge.Done
    return null
}

@Composable
fun ThreadBadge.color(): Color = when (this) {
    ThreadBadge.Approval, ThreadBadge.Limited -> T3.colors.warning
    ThreadBadge.Input -> MaterialTheme.colorScheme.tertiary
    ThreadBadge.Plan -> MaterialTheme.colorScheme.tertiary
    ThreadBadge.Working -> T3.colors.info
    ThreadBadge.Failed -> MaterialTheme.colorScheme.error
    ThreadBadge.Done -> T3.colors.success
}

@Composable
fun ThreadBadge.containerColor(): Color = when (this) {
    ThreadBadge.Approval, ThreadBadge.Limited -> T3.colors.warningContainer
    ThreadBadge.Input, ThreadBadge.Plan -> MaterialTheme.colorScheme.tertiaryContainer
    ThreadBadge.Working -> MaterialTheme.colorScheme.secondaryContainer
    ThreadBadge.Failed -> MaterialTheme.colorScheme.errorContainer
    ThreadBadge.Done -> T3.colors.successContainer
}
