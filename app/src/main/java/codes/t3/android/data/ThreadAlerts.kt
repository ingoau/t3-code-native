package codes.t3.android.data

import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.WorkingStatuses
import java.time.Instant

enum class AlertKind(val title: String) {
    Approval("Approval needed"),
    Input("Input needed"),
    Failed("Thread failed"),
    Limited("Usage limit reached"),
    Completed("Thread completed"),
}

data class ThreadAlert(val environmentId: String, val threadId: String, val kind: AlertKind, val threadTitle: String)

/**
 * Turns shell updates into "needs you" alerts, following the desktop app's notification coordinator:
 * - one attention alert per run and state (approval, input, failed, usage limit), keyed `runId:state`;
 * - one completion alert per newly completed run;
 * - nothing on first sight of a thread (initial load or reconnect), for archived threads or for subagent threads,
 *   though a parent inherits the approval/input its subagents are waiting on.
 */
class ThreadAlertTracker {
    private data class Seen(val attention: String?, val completion: Long?)
    private val seen = HashMap<String, Seen>()

    fun update(environmentId: String, threads: Collection<ThreadShell>): List<ThreadAlert> {
        val childRequests = HashMap<String, String>()
        for (t in threads) {
            val kind = t.pendingRuntimeRequest?.kind ?: continue
            val parent = t.parentThreadId ?: continue
            if (!t.isSubagent || kind == "auth_refresh") continue
            if (kind == "user_input") childRequests[parent] = "user_input" else childRequests.putIfAbsent(parent, "approval")
        }
        val alerts = mutableListOf<ThreadAlert>()
        val present = HashSet<String>()
        for (t in threads) {
            if (t.isSubagent) continue
            val key = "$environmentId/${t.id}"
            present += key
            val status = status(t, childRequests[t.id])
            val runId = t.latestRunId
            val attention = if (runId != null && status in AttentionStates) "$runId:${status!!.name}" else null
            val prior = seen[key]
            val completedAt = if (status == null && runId != null && latestRunStatus(t) == "completed") {
                (t.latestRunCompletedAt ?: t.updatedAt)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            } else null
            val completion = completedAt ?: prior?.completion
            seen[key] = Seen(attention, completion)
            if (prior == null || t.archivedAt != null) continue
            when {
                attention != null && attention != prior.attention -> alerts += ThreadAlert(environmentId, t.id, status!!, t.title)
                completedAt != null && (prior.completion == null || completedAt > prior.completion) ->
                    alerts += ThreadAlert(environmentId, t.id, AlertKind.Completed, t.title)
            }
        }
        seen.keys.removeAll { it.startsWith("$environmentId/") && it !in present }
        return alerts
    }

    /** Forget an environment (removed or disconnected) so its next snapshot is treated as first sight. */
    fun reset(environmentId: String) { seen.keys.removeAll { it.startsWith("$environmentId/") } }

    private fun latestRunStatus(t: ThreadShell): String = if (t.status == "idle") "completed" else t.status

    /** Attention state, or null when the thread is working, idle or ready (upstream `resolveSidebarThreadStatus`). */
    private fun status(t: ThreadShell, childRequest: String?): AlertKind? {
        val request = t.pendingRuntimeRequest?.kind?.takeIf { it != "auth_refresh" }
        if (request == "user_input" || childRequest == "user_input") return AlertKind.Input
        if (request != null || childRequest == "approval") return AlertKind.Approval
        val runtime = t.activityRunStatus ?: t.status
        if (runtime in WorkingStatuses) return null
        if (runtime == "failed") return if (t.lastErrorClass == "usage_limit") AlertKind.Limited else AlertKind.Failed
        return null
    }

    private companion object {
        val AttentionStates = setOf(AlertKind.Approval, AlertKind.Input, AlertKind.Failed, AlertKind.Limited)
    }
}
