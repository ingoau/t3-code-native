package codes.t3.android.ui.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SubdirectoryArrowLeft
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.state.Lineage
import codes.t3.android.data.state.LineageKind
import codes.t3.android.data.state.LineageRow
import codes.t3.android.ui.components.rememberHaptics
import codes.t3.android.ui.theme.T3
import codes.t3.android.ui.util.formatElapsed
import codes.t3.android.ui.util.parseInstant
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** Top-bar entry point to the lineage sheet; badged with the number of agents still running. */
@Composable
fun LineageButton(lineage: Lineage, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (lineage.runningCount > 0) Badge { Text("${lineage.runningCount}") } }) {
            Icon(Icons.Rounded.AccountTree, "Lineage")
        }
    }
}

/**
 * The thread's relationships, like the Lineage panel in the desktop app: its parent, the agents it is running, forks,
 * and (collapsed) the agents that already finished. Rows with a thread open it; provider-managed agents without one
 * only show their status, since their work is in this transcript.
 */
@Composable
fun LineageSheet(lineage: Lineage, onOpenThread: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LineageContent(lineage, onOpenThread)
    }
}

@Composable
fun LineageContent(lineage: Lineage, onOpenThread: (String) -> Unit, modifier: Modifier = Modifier) {
    val ticking = lineage.agents.isNotEmpty()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) { while (ticking) { now = System.currentTimeMillis(); delay(1000) } }
    var previousExpanded by rememberSaveable { mutableStateOf(lineage.agents.isEmpty() && lineage.forks.isEmpty()) }
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Text(
            if (lineage.runningCount > 0) "Lineage · ${lineage.runningCount} running" else "Lineage",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        val open: (LineageRow) -> Unit = { row -> row.threadId?.let { haptics.tick(); onOpenThread(it) } }
        lineage.parent?.let { Section(null, listOf(it), now, open) }
        Section(if (lineage.parent != null) "Agents" else null, lineage.agents, now, open)
        Section("Forks", lineage.forks, now, open)
        if (lineage.previousAgents.isNotEmpty()) {
            val rotation by animateFloatAsState(if (previousExpanded) 180f else 0f, label = "previous")
            val failed = lineage.previousAgents.count { it.failed }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { previousExpanded = !previousExpanded; haptics.tick() }
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Previous agents" + if (previousExpanded) "" else " (${lineage.previousAgents.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (failed > 0) Text("$failed failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Icon(Icons.Rounded.ExpandMore, if (previousExpanded) "Collapse" else "Expand", Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(previousExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Section(null, lineage.previousAgents, now, open)
            }
        }
    }
}

@Composable
private fun Section(title: String?, rows: List<LineageRow>, now: Long, onOpen: (LineageRow) -> Unit) {
    if (rows.isEmpty()) return
    if (title != null) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
        )
    }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            LineageRowItem(row, codes.t3.android.ui.home.segmentShape(index, rows.size), now, onOpen)
        }
    }
}

@Composable
private fun LineageRowItem(row: LineageRow, shape: RoundedCornerShape, now: Long, onOpen: (LineageRow) -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(enabled = row.openable) { onOpen(row) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (row.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            if (row.active && row.kind == LineageKind.Subagent) LoadingIndicator(Modifier.size(24.dp))
            else Icon(if (row.failed) Icons.Rounded.ErrorOutline else row.kind.icon(), null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.kind.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = row.subagent?.progress?.takeIf { row.active }
                    ?: if (!row.openable && row.kind == LineageKind.Subagent) "Runs inside this thread" else null
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                StatusText(row, now)
                if (row.openable) Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open", Modifier.padding(top = 2.dp).size(16.dp).alpha(0.7f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusText(row: LineageRow, now: Long) {
    val color = when {
        row.failed -> MaterialTheme.colorScheme.error
        row.active -> MaterialTheme.colorScheme.primary
        row.status == "completed" -> T3.colors.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(lineageStatusLabel(row, Instant.ofEpochMilli(now)), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}

/** `Running 1m 04s`, `Done · 2m 10s`, `Failed`, … — elapsed time only when the agent reported its start. */
fun lineageStatusLabel(row: LineageRow, now: Instant): String {
    val start = parseInstant(row.startedAt)
    val end = parseInstant(row.completedAt) ?: if (row.active) now else null
    val elapsed = if (start != null && end != null) formatElapsed(Duration.between(start, end)) else null
    val label = when (row.status) {
        "running" -> "Running"
        "pending", "queued", "preparing", "starting" -> "Starting"
        "waiting" -> "Waiting"
        "completed" -> "Done"
        "failed", "error" -> "Failed"
        "cancelled", "interrupted" -> "Stopped"
        "idle" -> "Idle"
        null -> ""
        else -> row.status.replaceFirstChar { it.uppercase() }
    }
    return when {
        row.kind != LineageKind.Subagent -> label
        row.active && elapsed != null -> "$label $elapsed"
        elapsed != null && row.status == "completed" -> "$label · $elapsed"
        else -> label
    }
}

private fun LineageKind.icon(): ImageVector = when (this) {
    LineageKind.Parent, LineageKind.ParentAgent -> Icons.Rounded.SubdirectoryArrowLeft
    LineageKind.Subagent -> Icons.Rounded.SmartToy
    LineageKind.Fork -> Icons.Rounded.CallSplit
}

private fun LineageKind.label(): String = when (this) {
    LineageKind.Parent -> "Parent thread"
    LineageKind.ParentAgent -> "Parent agent"
    LineageKind.Subagent -> "Subagent"
    LineageKind.Fork -> "Fork"
}

/**
 * Replaces the composer on a subagent the provider started itself: it runs on its own and can't take messages, so
 * show what it is doing and a way back to the agent that spawned it (as the upstream mobile app does).
 */
@Composable
fun SubagentBar(shell: ThreadShell, parent: LineageRow?, model: String?, working: Boolean, onOpenParent: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(working) { while (working) { now = System.currentTimeMillis(); delay(1000) } }
    val start = parseInstant(shell.activityRunStartedAt ?: shell.latestRunStartedAt)
    val end = if (working) Instant.ofEpochMilli(now) else parseInstant(shell.latestRunCompletedAt)
    val elapsed = if (start != null && end != null) " " + formatElapsed(Duration.between(start, end)) else ""
    val status = when {
        working -> "Running$elapsed"
        shell.status == "failed" -> "Failed"
        shell.status == "completed" -> if (elapsed.isEmpty()) "Done" else "Done ·$elapsed"
        else -> shell.status.replaceFirstChar { it.uppercase() }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (working) LoadingIndicator(Modifier.size(28.dp))
            else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.SmartToy, null, Modifier.padding(5.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull("Subagent", model).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$status · Runs on its own",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onOpenParent != null && parent != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onOpenParent) { Text("Open parent") }
            }
        }
    }
}
