package codes.t3.android.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.model.ServerProvider
import codes.t3.android.data.model.UsageLimits
import codes.t3.android.data.model.UsageWindow
import codes.t3.android.ui.theme.T3
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Context window fill of the thread's provider session. */
data class ContextUsage(val used: Long, val max: Long?) {
    val fraction: Float? get() = max?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }
}

enum class CommandKind { Builtin, Provider, Skill }

/** A composer `/` (or `$`) suggestion. Built-ins act locally; provider commands and skills insert text to send. */
data class ComposerCommand(val id: String, val label: String, val description: String?, val kind: CommandKind) {
    val insertText: String get() = if (kind == CommandKind.Skill) "\$$id " else "/$id "
}

/** Built-ins as upstream: `/model`, `/plan` and `/default` (when the provider has modes), and `/usage-limits`. */
fun builtinCommands(provider: ServerProvider?, hasThread: Boolean): List<ComposerCommand> = buildList {
    add(ComposerCommand("model", "/model", "Switch model", CommandKind.Builtin))
    if (provider?.showInteractionModeToggle != false) {
        add(ComposerCommand("plan", "/plan", "Switch to plan mode", CommandKind.Builtin))
        add(ComposerCommand("default", "/default", "Switch to default mode", CommandKind.Builtin))
    }
    if (hasThread && provider?.usageLimits != null) add(ComposerCommand("usage-limits", "/usage-limits", "Show this provider's usage limits", CommandKind.Builtin))
}

/**
 * Suggestions for what's being typed: `/query` at the start of the message lists built-ins, the provider's own
 * commands and its skills; `$query` lists skills. Null when the text isn't a command trigger.
 */
fun commandSuggestions(text: String, provider: ServerProvider?, builtins: List<ComposerCommand>): List<ComposerCommand>? {
    val slash = Regex("""^/(\S*)$""").find(text)?.groupValues?.get(1)
    val dollar = Regex("""^\$(\S*)$""").find(text)?.groupValues?.get(1)
    val query = (slash ?: dollar ?: return null).lowercase().removePrefix("skill:")
    val skills = provider?.skills.orEmpty()
        .filter { it.enabled && it.userInvocable != false }
        .distinctBy { it.name }
        .map { ComposerCommand(it.name, "\$" + (it.displayName ?: it.name), it.shortDescription ?: it.description, CommandKind.Skill) }
    val skillNames = skills.map { it.id }.toSet()
    val all = if (dollar != null) skills else builtins + provider?.slashCommands.orEmpty()
        .filter { it.name !in skillNames && builtins.none { b -> b.id == it.name } }
        .map { ComposerCommand(it.name, "/${it.name}", it.description, CommandKind.Provider) } + skills
    fun matches(c: ComposerCommand) = query.isEmpty() || c.id.lowercase().contains(query) || c.label.lowercase().contains(query) ||
        c.description?.lowercase()?.contains(query) == true
    return all.filter(::matches).sortedBy { if (it.id.lowercase().startsWith(query)) 0 else 1 }.take(20)
}

@Composable
fun CommandSuggestions(commands: List<ComposerCommand>, onPick: (ComposerCommand) -> Unit, modifier: Modifier = Modifier) {
    if (commands.isEmpty()) return
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = 260.dp).padding(vertical = 6.dp)) {
            items(commands, key = { "${it.kind}:${it.id}" }) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(c) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(c.icon(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(c.label, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    c.description?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun ComposerCommand.icon(): ImageVector = when {
    kind == CommandKind.Skill -> Icons.Rounded.AutoAwesome
    kind == CommandKind.Provider -> Icons.Rounded.Terminal
    id == "model" -> Icons.Rounded.Tune
    id == "plan" -> Icons.Rounded.Lightbulb
    id == "default" -> Icons.Rounded.Construction
    else -> Icons.Rounded.Speed
}

/** Toolbar ring showing how full the context window is; opens the usage sheet. */
@Composable
fun ContextChip(usage: ContextUsage, onClick: () -> Unit) {
    val fraction = usage.fraction
    val label = contextLabel(usage)
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.semantics { contentDescription = label }) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (fraction != null) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round,
                    color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            } else Icon(Icons.Rounded.DataUsage, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun contextLabel(usage: ContextUsage): String {
    val f = usage.fraction ?: return "Context window ${formatTokens(usage.used)} tokens used"
    return "Context window ${formatPercent(f * 100)} used"
}

/** `840`, `84k`, `1.2m`. */
fun formatTokens(n: Long): String = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> String.format(Locale.ROOT, if (n < 10_000) "%.1fk" else "%.0fk", n / 1_000.0).replace(".0k", "k")
    else -> String.format(Locale.ROOT, "%.1fm", n / 1_000_000.0).replace(".0m", "m")
}

private fun formatPercent(p: Float): String = if (p < 10) String.format(Locale.ROOT, "%.1f%%", p) else "${p.toInt()}%"

/** `resets in 2h 14m`, as upstream `formatResetsIn`. */
fun formatResetsIn(resetsAt: String?, now: Instant = Instant.now()): String? {
    val at = resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val d = Duration.between(now, at)
    if (d.isNegative || d.isZero) return "resets now"
    val m = d.toMinutes()
    return when {
        m < 1 -> "Resets momentarily"
        m < 60 -> "resets in ${m}m"
        m < 60 * 24 -> "resets in ${m / 60}h ${m % 60}m"
        else -> "resets in ${m / (60 * 24)}d ${(m / 60) % 24}h"
    }
}

/** Context window plus the provider's rate-limit windows (percent left, reset time), like upstream `/usage-limits`. */
@Composable
fun UsageSheet(context: ContextUsage?, provider: ServerProvider?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        UsageContent(context, provider)
    }
}

@Composable
fun UsageContent(context: ContextUsage?, provider: ServerProvider?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Usage", style = MaterialTheme.typography.headlineSmall)
        if (context != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("Context window", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val f = context.fraction
                    Text(
                        if (f != null) "${formatPercent(f * 100)} · ${formatTokens(context.used)}/${formatTokens(context.max!!)}" else "${formatTokens(context.used)} tokens",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                context.fraction?.let { UsageBar(it, if (it > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Text("Context compacts automatically when needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val limits: UsageLimits? = provider?.usageLimits
        if (provider != null) {
            Text("${provider.label} limits" + (provider.auth?.label?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
            when {
                limits == null -> Text("This provider does not currently report limits.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                limits.unavailable != null -> Text(
                    limits.unavailable.message ?: if (limits.unavailable.reason == "probeFailed") "Could not read limits." else "This account has no subscription limits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                limits.windows.isEmpty() -> Text("No limits reported.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> limits.windows.forEach { WindowRow(it) }
            }
        }
    }
}

@Composable
private fun WindowRow(w: UsageWindow) {
    val left = (100 - w.usedPercent).coerceIn(0.0, 100.0)
    val color = when {
        left <= 10 -> MaterialTheme.colorScheme.error
        left <= 30 -> T3.colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${left.toInt()}% left", style = MaterialTheme.typography.labelLarge, color = color)
        }
        UsageBar((w.usedPercent / 100).toFloat().coerceIn(0f, 1f), color)
        formatResetsIn(w.resetsAt)?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun UsageBar(fraction: Float, color: Color) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
    )
}
