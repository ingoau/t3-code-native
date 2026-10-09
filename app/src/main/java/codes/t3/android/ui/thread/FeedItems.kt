package codes.t3.android.ui.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LiveHelp
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import codes.t3.android.data.model.TurnItem
import codes.t3.android.ui.components.ShimmerText
import codes.t3.android.ui.markdown.T3Markdown
import codes.t3.android.ui.theme.LocalT3Colors
import codes.t3.android.ui.theme.MonoStyle
import codes.t3.android.ui.theme.T3
import codes.t3.android.ui.util.clockTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Callbacks a feed row may need. */
data class FeedActions(
    val onImplementPlan: (TurnItem) -> Unit = {},
    val loadFullItem: suspend (String) -> TurnItem? = { null },
)

@Composable
fun FeedRow(entry: FeedEntry, wrapCode: Boolean, actions: FeedActions, modifier: Modifier = Modifier) {
    when (entry) {
        is FeedEntry.User -> UserBubble(entry.item, modifier)
        is FeedEntry.Assistant -> AssistantMessage(entry.item, entry.final, wrapCode, modifier)
        is FeedEntry.Work -> WorkGroup(entry, modifier, actions)
        is FeedEntry.Fold -> FoldRow(entry, wrapCode, actions, modifier)
        is FeedEntry.Plan -> PlanCard(entry.item, actions, modifier)
        is FeedEntry.Todo -> TodoCard(entry.item, modifier)
        is FeedEntry.Error -> ErrorCard(entry.item, modifier)
        is FeedEntry.Checkpoint -> CheckpointCard(entry.item, modifier)
        is FeedEntry.Notice -> NoticeRow(entry.text, modifier)
        FeedEntry.Thinking -> ThinkingRow(modifier)
    }
}

// ------------------------------------------------------------------ messages

@Composable
fun UserBubble(item: TurnItem, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                val count = item.attachments.size
                if (count > 0) {
                    Text(
                        if (count == 1) "1 attachment" else "$count attachments",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            when (item.inputIntent) {
                "queued_turn" -> IntentBadge("queued", T3.colors.warning)
                "steer", "promoted_queued_to_steer" -> IntentBadge("steer", T3.colors.info)
            }
            Text(
                clockTime(item.startedAt ?: item.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyButton(item.text) { clipboard.setText(AnnotatedString(item.text)) }
        }
    }
}

@Composable
private fun IntentBadge(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f), modifier = Modifier.padding(end = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun CopyButton(text: String, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    IconButton(onClick = { onCopy(); copied = true }, modifier = Modifier.size(36.dp)) {
        Icon(
            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            if (copied) "Copied" else "Copy",
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AssistantMessage(item: TurnItem, final: Boolean, wrapCode: Boolean, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SelectionContainer { T3Markdown(item.text, wrapCode = wrapCode) }
        if (final && !item.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CopyButton(item.text) { clipboard.setText(AnnotatedString(item.text)) }
                Text(
                    clockTime(item.completedAt ?: item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ work log

fun workIcon(item: TurnItem): ImageVector = when (item.type) {
    "command_execution" -> Icons.Rounded.Terminal
    "file_change" -> Icons.Rounded.EditNote
    "file_search" -> Icons.Rounded.Search
    "web_search" -> Icons.Rounded.Language
    "reasoning" -> Icons.Rounded.Psychology
    "subagent" -> Icons.Rounded.SmartToy
    "approval_request" -> Icons.Rounded.Shield
    "user_input_request" -> Icons.Rounded.LiveHelp
    "secret_request" -> Icons.Rounded.Key
    "notification" -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Build
}

fun workLabel(item: TurnItem): String = when (item.type) {
    "command_execution" -> item.commandInput?.lineSequence()?.firstOrNull()?.trim().orEmpty().ifEmpty { "Ran a command" }
    "file_change" -> {
        val name = item.fileName?.substringAfterLast('/') ?: item.title ?: "files"
        "Edited $name"
    }
    "file_search" -> item.str("pattern")?.let { "Searched “$it”" } ?: item.title ?: "Searched files"
    "web_search" -> item.arr("patterns")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { "Searched the web for “$it”" } ?: "Searched the web"
    "reasoning" -> if (item.isRunning) "Thinking" else "Thought"
    "subagent" -> "Subagent: " + (item.str("prompt")?.lineSequence()?.firstOrNull() ?: "task")
    "approval_request" -> "Approval requested"
    "user_input_request" -> "Input requested"
    "secret_request" -> item.str("label") ?: "Secret requested"
    "notification" -> item.str("summary") ?: "Notification"
    "dynamic_tool" -> item.title ?: item.toolName ?: "Used a tool"
    else -> item.title ?: item.type.replace('_', ' ')
}

private fun liveLabel(item: TurnItem): String = when (item.type) {
    "command_execution" -> "Running " + (item.commandInput?.trim()?.substringBefore(' ')?.substringAfterLast('/') ?: "command")
    "file_change" -> "Editing " + (item.fileName?.substringAfterLast('/') ?: "files")
    "file_search" -> "Searching code"
    "web_search" -> "Searching the web"
    "reasoning" -> item.text.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.trim('*')?.take(80) ?: "Thinking"
    else -> workLabel(item)
}

@Composable
fun WorkGroup(entry: FeedEntry.Work, modifier: Modifier = Modifier, actions: FeedActions = FeedActions()) {
    val items = entry.items
    val single = items.size == 1
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    val running = items.lastOrNull { it.isRunning }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (single) {
            WorkRow(items.first(), actions)
        } else {
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chev")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.live && running != null) {
                    LoadingIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    ShimmerText(liveLabel(running), MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                } else {
                    Icon(workIcon(items.first()), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        summarizeWork(items),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) { items.forEach { WorkRow(it, actions) } }
                }
            }
        }
    }
}

@Composable
fun WorkRow(item: TurnItem, actions: FeedActions = FeedActions()) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var full by remember(item.id) { mutableStateOf<TurnItem?>(null) }
    LaunchedEffect(expanded, item.outputOmitted) {
        if (expanded && item.outputOmitted && full == null) full = runCatching { actions.loadFullItem(item.id) }.getOrNull()
    }
    val shown = full ?: item
    val failed = item.isFailed && item.status != "running"
    val hasDetail = detailText(shown) != null
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = hasDetail) { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isRunning) LoadingIndicator(Modifier.size(18.dp))
            else Icon(
                if (failed) Icons.Rounded.ErrorOutline else workIcon(item),
                null,
                Modifier.size(16.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            val label = if (item.isRunning) liveLabel(item) else workLabel(item)
            val mono = item.type == "command_execution"
            if (item.isRunning) {
                ShimmerText(label, (if (mono) MonoStyle else MaterialTheme.typography.bodyMedium), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            } else {
                Text(
                    label,
                    style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (item.type == "file_change") DiffStat(item.additions, item.deletions)
        }
        if (expanded) {
            val detail = detailText(shown)
            if (detail != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = LocalT3Colors.current.codeBackground,
                    modifier = Modifier.fillMaxWidth().padding(start = 34.dp, end = 4.dp, bottom = 6.dp),
                ) {
                    Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                if (item.type == "file_change") diffAnnotated(detail) else AnnotatedString(detail),
                                style = if (item.type == "reasoning") MaterialTheme.typography.bodyMedium else MonoStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = item.type == "reasoning",
                                modifier = Modifier
                                    .then(if (item.type == "reasoning") Modifier else Modifier.horizontalScroll(rememberScrollState()))
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun detailText(item: TurnItem): String? = when (item.type) {
    "command_execution" -> buildString {
        append("$ ").append(item.commandInput.orEmpty())
        val out = item.output
        if (!out.isNullOrBlank()) append("\n").append(out.trimEnd())
        else if (item.outputOmitted) append("\nLoading output…")
        else if (item.status == "completed") append("\nNo output.")
        item.exitCode?.let { append("\n\nexit ").append(it) }
    }
    "file_change" -> item.diff ?: item.str("newStr")
    "reasoning" -> item.text.ifBlank { null }
    "dynamic_tool" -> listOfNotNull(
        item.raw["input"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { "input: $it" },
        item.raw["output"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let {
            "output: " + ((it as? JsonPrimitive)?.contentOrNull ?: it.toString())
        },
    ).joinToString("\n\n").ifBlank { null }
    "file_search" -> item.arr("results")?.joinToString("\n") { r ->
        val o = r as? JsonObject
        val file = (o?.get("fileName") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val line = (o?.get("line") as? JsonPrimitive)?.intOrNull
        if (line != null) "$file:$line" else file
    }?.ifBlank { null }
    "web_search" -> item.arr("results")?.joinToString("\n") { r ->
        val o = r as? JsonObject
        listOfNotNull((o?.get("title") as? JsonPrimitive)?.contentOrNull, (o?.get("url") as? JsonPrimitive)?.contentOrNull).joinToString(" — ")
    }?.ifBlank { null }
    "subagent" -> listOfNotNull(item.str("prompt"), item.str("result")).joinToString("\n\n").ifBlank { null }
    "notification" -> item.str("detail")
    else -> null
}

@Composable
private fun diffAnnotated(diff: String): AnnotatedString {
    val added = T3.colors.diffAdded
    val removed = T3.colors.diffRemoved
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        diff.lines().forEachIndexed { i, line ->
            if (i > 0) append('\n')
            val color = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> meta
                line.startsWith("+") -> added
                line.startsWith("-") -> removed
                else -> null
            }
            if (color != null) withStyle(SpanStyle(color = color)) { append(line) } else append(line)
        }
    }
}

@Composable
fun DiffStat(additions: Int?, deletions: Int?) {
    if (additions == null && deletions == null) return
    Row {
        additions?.let { Text("+$it", style = MonoStyle, color = T3.colors.diffAdded) }
        Spacer(Modifier.width(6.dp))
        deletions?.let { Text("−$it", style = MonoStyle, color = T3.colors.diffRemoved) }
    }
}

@Composable
fun FoldRow(entry: FeedEntry.Fold, wrapCode: Boolean, actions: FeedActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "fold")
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            Modifier
                .clip(CircleShape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Timelapse, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(entry.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Rounded.ExpandMore, if (expanded) "Collapse" else "Expand", Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 4.dp)) {
                entry.entries.forEach { FeedRow(it, wrapCode, actions) }
            }
        }
    }
}

// ------------------------------------------------------------------ cards

@Composable
fun PlanCard(item: TurnItem, actions: FeedActions, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text("Proposed plan", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                if (item.streaming) {
                    Spacer(Modifier.width(8.dp))
                    LoadingIndicator(Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.size(8.dp))
            T3Markdown(item.text)
            if (!item.streaming) {
                Spacer(Modifier.size(12.dp))
                Button(onClick = { actions.onImplementPlan(item) }) { Text("Implement plan") }
            }
        }
    }
}

@Composable
fun TodoCard(item: TurnItem, modifier: Modifier = Modifier) {
    val steps = item.arr("steps").orEmpty().mapNotNull { it as? JsonObject }
    val done = steps.count { (it["status"] as? JsonPrimitive)?.contentOrNull == "completed" }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Plan · $done of ${steps.size} done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            steps.forEach { step ->
                val status = (step["status"] as? JsonPrimitive)?.contentOrNull
                val text = (step["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (status) {
                        "completed" -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = T3.colors.success)
                        "running" -> LoadingIndicator(Modifier.size(18.dp))
                        else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorCard(item: TurnItem, modifier: Modifier = Modifier) {
    val failure = item.obj("failure")
    val message = (failure?.get("message") as? JsonPrimitive)?.contentOrNull ?: item.text.ifBlank { "Something went wrong." }
    val limited = (failure?.get("class") as? JsonPrimitive)?.contentOrNull == "usage_limit"
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (limited) T3.colors.warningContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (limited) T3.colors.onWarningContainer else MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (limited) "Usage limit reached" else "Provider error", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun CheckpointCard(item: TurnItem, modifier: Modifier = Modifier) {
    val files = item.arr("files").orEmpty().mapNotNull { it as? JsonObject }
    if (files.isEmpty()) return
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val adds = files.sumOf { (it["additions"] as? JsonPrimitive)?.intOrNull ?: 0 }
    val dels = files.sumOf { (it["deletions"] as? JsonPrimitive)?.intOrNull ?: 0 }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Difference, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (files.size == 1) "1 file changed" else "${files.size} files changed",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                DiffStat(adds, dels)
            }
            if (expanded) {
                Spacer(Modifier.size(6.dp))
                files.forEach { f ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            (f["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            style = MonoStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        DiffStat((f["additions"] as? JsonPrimitive)?.intOrNull, (f["deletions"] as? JsonPrimitive)?.intOrNull)
                    }
                }
            }
        }
    }
}

@Composable
fun NoticeRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 260.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ThinkingRow(modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        ShimmerText("Thinking", MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

