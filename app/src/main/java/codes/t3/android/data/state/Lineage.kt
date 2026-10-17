package codes.t3.android.data.state

import codes.t3.android.data.model.Subagent
import codes.t3.android.data.model.ThreadShell

/** How a related thread relates to the open one. */
enum class LineageKind { Parent, ParentAgent, Subagent, Fork }

/**
 * One row of a thread's lineage: its parent, the agents it spawned and the threads forked from it. [threadId] is null
 * for a provider-managed agent with no thread of its own (its work only shows in this transcript).
 */
data class LineageRow(
    val kind: LineageKind,
    val threadId: String?,
    val title: String,
    /** Run status for agents and threads (`running`, `completed`, `failed`, …). */
    val status: String?,
    val thread: ThreadShell? = null,
    val subagent: Subagent? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
) {
    val key: String get() = threadId ?: "agent:${subagent?.id}"
    val openable: Boolean get() = threadId != null && thread != null
    val active: Boolean get() = status in ActiveStatuses
    val failed: Boolean get() = status == "failed" || status == "error"
}

data class Lineage(
    val parent: LineageRow? = null,
    /** Agents still working, newest first. */
    val agents: List<LineageRow> = emptyList(),
    /** Finished agents, newest first. */
    val previousAgents: List<LineageRow> = emptyList(),
    val forks: List<LineageRow> = emptyList(),
) {
    val isEmpty: Boolean get() = parent == null && agents.isEmpty() && previousAgents.isEmpty() && forks.isEmpty()
    val runningCount: Int get() = agents.count { it.status == "running" || it.status == "waiting" }
    val size: Int get() = listOfNotNull(parent).size + agents.size + previousAgents.size + forks.size
}

private val ActiveStatuses = setOf("preparing", "queued", "starting", "pending", "running", "waiting")

/**
 * The open thread's immediate relationships, like the desktop app's Lineage panel: the parent (or parent agent), the
 * subagents this thread spawned (from its projection, so provider-managed agents without a thread are included, plus
 * any child threads the shell knows about), and its forks.
 */
fun deriveLineage(threadId: String, threads: Map<String, ThreadShell>, subagents: Collection<Subagent>): Lineage {
    val self = threads[threadId]
    val parent = self?.parentThreadId?.takeIf { it != threadId }?.let { parentId ->
        val thread = threads[parentId]
        LineageRow(
            kind = if (self.isSubagent) LineageKind.ParentAgent else LineageKind.Parent,
            threadId = parentId,
            title = thread?.title?.ifBlank { null } ?: "Parent thread",
            status = thread?.let { it.activityRunStatus ?: it.status },
            thread = thread,
        )
    }

    val agentRows = LinkedHashMap<String, LineageRow>()
    for (agent in subagents.filter { it.threadId.isEmpty() || it.threadId == threadId }) {
        val child = agent.childThreadId?.let { threads[it] }
        // A child thread that started a newer run than the agent record knows about is the fresher source.
        val childStatus = child?.activityRunStatus
        val row = LineageRow(
            kind = LineageKind.Subagent,
            threadId = agent.childThreadId,
            title = subagentTitle(agent.title ?: child?.title, agent.prompt),
            status = childStatus ?: agent.status.let { if (it == "rolled_back") "interrupted" else it },
            thread = child,
            subagent = agent,
            startedAt = if (childStatus != null) child?.activityRunStartedAt else agent.startedAt,
            completedAt = if (childStatus != null) null else agent.completedAt,
        )
        agentRows[row.key] = row
    }
    val forks = mutableListOf<LineageRow>()
    for (thread in threads.values) {
        if (thread.id == threadId || thread.parentThreadId != threadId || thread.deletedAt != null) continue
        val status = thread.activityRunStatus ?: thread.status
        if (thread.isSubagent) {
            if (agentRows.containsKey(thread.id)) continue
            agentRows[thread.id] = LineageRow(
                LineageKind.Subagent, thread.id, subagentTitle(thread.title, ""), status, thread,
                startedAt = thread.activityRunStartedAt ?: thread.latestRunStartedAt,
                completedAt = if (thread.activityRunStatus != null) null else thread.latestRunCompletedAt,
            )
        } else {
            forks += LineageRow(LineageKind.Fork, thread.id, thread.title.ifBlank { "Fork" }, status, thread)
        }
    }

    val newestFirst = compareByDescending<LineageRow> { it.startedAt ?: it.thread?.createdAt ?: it.subagent?.updatedAt ?: "" }
    val (active, previous) = agentRows.values.sortedWith(newestFirst).partition { it.active }
    return Lineage(
        parent = parent,
        agents = active,
        previousAgents = previous,
        forks = forks.sortedByDescending { it.thread?.createdAt ?: "" },
    )
}

/** Subagent threads are titled like "Explore: find the reconnect logic"; keep the task, as the desktop app does. */
fun subagentTitle(title: String?, prompt: String): String {
    val raw = title?.trim()?.ifBlank { null } ?: prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return "Agent"
    return raw.removePrefix("Subagent:").trim().take(120).ifBlank { "Agent" }
}

/** Running subagent threads per parent thread, for inbox badges. */
fun runningAgentsByParent(threads: Collection<ThreadShell>): Map<String, Int> = threads
    .filter { it.isSubagent && it.archivedAt == null && it.isWorking }
    .mapNotNull { it.parentThreadId }
    .groupingBy { it }
    .eachCount()
