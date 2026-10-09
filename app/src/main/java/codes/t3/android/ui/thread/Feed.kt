package codes.t3.android.ui.thread

import codes.t3.android.data.model.Run
import codes.t3.android.data.model.TurnItem
import codes.t3.android.ui.util.parseInstant
import java.time.Duration

/** Items rendered as tool/work rows (collapsible). */
private val WorkTypes = setOf(
    "command_execution", "file_change", "file_search", "web_search", "dynamic_tool", "reasoning",
    "subagent", "secret_request", "notification", "approval_request", "user_input_request",
)

/** Items that always stay visible, even inside a finished turn's fold. */
private val ProminentTypes = setOf("proposed_plan", "todo_list", "error", "checkpoint")

sealed interface FeedEntry {
    val key: String

    data class User(val item: TurnItem) : FeedEntry { override val key = "u-${item.id}" }
    data class Assistant(val item: TurnItem, val final: Boolean) : FeedEntry { override val key = "a-${item.id}" }
    /** Consecutive tool rows. [live] when the turn is still running. */
    data class Work(override val key: String, val items: List<TurnItem>, val live: Boolean) : FeedEntry
    /** A finished turn's intermediate work, folded into "Worked for 2m 14s". */
    data class Fold(override val key: String, val label: String, val entries: List<FeedEntry>) : FeedEntry
    data class Plan(val item: TurnItem) : FeedEntry { override val key = "p-${item.id}" }
    data class Todo(val item: TurnItem) : FeedEntry { override val key = "t-${item.id}" }
    data class Error(val item: TurnItem) : FeedEntry { override val key = "e-${item.id}" }
    data class Checkpoint(val item: TurnItem) : FeedEntry { override val key = "c-${item.id}" }
    data class Notice(val item: TurnItem, val text: String) : FeedEntry { override val key = "n-${item.id}" }
    data object Thinking : FeedEntry { override val key = "thinking" }
}

fun summarizeWork(items: List<TurnItem>): String {
    val counts = items.groupingBy { it.type }.eachCount()
    val parts = buildList {
        counts["command_execution"]?.let { add(if (it == 1) "Ran 1 command" else "Ran $it commands") }
        counts["file_change"]?.let { add(if (it == 1) "edited 1 file" else "edited $it files") }
        counts["file_search"]?.let { add(if (it == 1) "searched code" else "searched code $it times") }
        counts["web_search"]?.let { add(if (it == 1) "searched the web" else "searched the web $it times") }
        counts["dynamic_tool"]?.let { add(if (it == 1) "used 1 tool" else "used $it tools") }
        counts["subagent"]?.let { add(if (it == 1) "ran 1 subagent" else "ran $it subagents") }
        val thoughts = counts["reasoning"]
        if (thoughts != null && size == 0) add("Thought")
    }
    if (parts.isEmpty()) return "Worked"
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}

private fun notice(item: TurnItem): String? = when (item.type) {
    "run_interrupt_request" -> "Interrupt requested"
    "run_interrupt_result" -> item.text.ifBlank { "Run interrupted" }
    "system_notice" -> item.text.ifBlank { null }
    "compaction" -> "Context compacted"
    "handoff" -> "Context handed off"
    "fork" -> "Thread forked"
    "thread_created" -> "Thread created"
    else -> null
}

private fun runDuration(run: Run?, items: List<TurnItem>): Duration? {
    val start = parseInstant(run?.workStartedAt ?: run?.startedAt) ?: items.mapNotNull { parseInstant(it.startedAt) }.minOrNull()
    val end = parseInstant(run?.completedAt) ?: items.mapNotNull { parseInstant(it.completedAt ?: it.updatedAt) }.maxOrNull()
    if (start == null || end == null || end.isBefore(start)) return null
    return Duration.between(start, end)
}

private fun foldLabel(run: Run?, items: List<TurnItem>): String {
    val d = runDuration(run, items)
    val stopped = run?.status == "interrupted" || run?.status == "cancelled"
    return when {
        d == null -> if (stopped) "You stopped this response" else "Worked"
        stopped -> "You stopped after ${codes.t3.android.ui.util.formatElapsed(d)}"
        else -> "Worked for ${codes.t3.android.ui.util.formatElapsed(d)}"
    }
}

/** Turn the ordered timeline into feed entries. */
fun buildFeed(timeline: List<TurnItem>, runs: Map<String, Run>, activeRunId: String?): List<FeedEntry> {
    val out = mutableListOf<FeedEntry>()
    var thinking = false
    // Group contiguous items by run, preserving order.
    val segments = mutableListOf<Pair<String?, MutableList<TurnItem>>>()
    for (item in timeline) {
        if (item.type == "assistant_message" && item.text.isBlank() && !item.streaming) continue
        // Pending approvals/questions are shown as cards above the composer instead.
        if ((item.type == "approval_request" || item.type == "user_input_request") && item.isRunning) continue
        val last = segments.lastOrNull()
        if (last != null && last.first == item.runId && item.runId != null) last.second += item
        else segments += item.runId to mutableListOf(item)
    }
    for ((runId, items) in segments) {
        val run = runId?.let { runs[it] }
        val live = runId != null && (runId == activeRunId || run?.status in setOf("preparing", "starting", "running", "waiting"))
        val users = items.filter { it.type == "user_message" }
        users.forEach { out += FeedEntry.User(it) }
        val rest = items.filter { it.type != "user_message" }
        if (rest.isEmpty()) {
            if (live) thinking = true
            continue
        }
        if (live || runId == null) {
            out += flatten(rest, live, runId ?: rest.first().id, finalAssistantId = null)
            // "Thinking" fills the gap while nothing is visibly running, but not right after a finished answer.
            if (live && rest.none { it.isRunning } && rest.last().type != "assistant_message") thinking = true
        } else {
            val finalAssistant = rest.lastOrNull { it.type == "assistant_message" }
            val folded = rest.filter { it.id != finalAssistant?.id && it.type !in ProminentTypes }
            if (folded.isNotEmpty() && folded.any { it.type in WorkTypes || it.type == "assistant_message" }) {
                out += FeedEntry.Fold("f-$runId-${folded.first().id}", foldLabel(run, items), flatten(folded, false, runId, null))
            } else {
                out += flatten(folded, false, runId, null)
            }
            rest.filter { it.type in ProminentTypes }.forEach { out += single(it) }
            finalAssistant?.let { out += FeedEntry.Assistant(it, final = true) }
        }
    }
    // At most one "Thinking" row, always at the end; keys must be unique for the lazy list.
    if (thinking) out += FeedEntry.Thinking
    return out.distinctBy { it.key }
}

private fun single(item: TurnItem): FeedEntry = when (item.type) {
    "proposed_plan" -> FeedEntry.Plan(item)
    "todo_list" -> FeedEntry.Todo(item)
    "error" -> FeedEntry.Error(item)
    "checkpoint" -> FeedEntry.Checkpoint(item)
    "assistant_message" -> FeedEntry.Assistant(item, final = false)
    else -> notice(item)?.let { FeedEntry.Notice(item, it) } ?: FeedEntry.Work("w-${item.id}", listOf(item), live = false)
}

private fun flatten(items: List<TurnItem>, live: Boolean, groupKey: String, finalAssistantId: String?): List<FeedEntry> {
    val out = mutableListOf<FeedEntry>()
    var work = mutableListOf<TurnItem>()
    fun flush() {
        if (work.isNotEmpty()) {
            out += FeedEntry.Work("w-$groupKey-${work.first().id}", work, live)
            work = mutableListOf()
        }
    }
    for (item in items) {
        if (item.type in WorkTypes) {
            work += item
        } else {
            flush()
            out += if (item.type == "assistant_message") FeedEntry.Assistant(item, final = item.id == finalAssistantId) else single(item)
        }
    }
    flush()
    return out
}
