package codes.t3.android.data.state

import codes.t3.android.data.model.ProjectShell
import codes.t3.android.data.model.ShellSnapshot
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.ThreadShell
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

/** Projects + thread list for one environment, kept in sync with `orchestration.subscribeShell`. */
data class ShellState(
    val sequence: Long = -1,
    val projects: Map<String, ProjectShell> = emptyMap(),
    val threads: Map<String, ThreadShell> = emptyMap(),
    val synchronized: Boolean = false,
) {
    val loaded: Boolean get() = sequence >= 0

    /** Apply one stream item (`snapshot`, `synchronized`, `project.*`, `thread.*`). Unknown kinds are ignored. */
    fun apply(item: JsonElement): ShellState {
        val obj = item as? JsonObject ?: return this
        val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
        if (kind == "snapshot") {
            val snapshot = T3Json.decodeFromJsonElement<ShellSnapshot>(obj["snapshot"] ?: return this)
            // A late snapshot carrying resolvedRepositoryIdentityRoots is only an enrichment refresh.
            if (obj.containsKey("resolvedRepositoryIdentityRoots") && loaded) {
                return copy(projects = projects + snapshot.projects.associateBy { it.id })
            }
            return ShellState(
                sequence = snapshot.snapshotSequence,
                projects = snapshot.projects.associateBy { it.id },
                threads = snapshot.threads.associateBy { it.id },
                synchronized = synchronized,
            )
        }
        if (kind == "synchronized") return copy(synchronized = true)
        val seq = (obj["sequence"] as? JsonPrimitive)?.longOrNull ?: return this
        if (seq <= sequence) return this
        val next = when (kind) {
            "project.updated" -> {
                val project = T3Json.decodeFromJsonElement<ProjectShell>(obj["project"] ?: return this)
                copy(projects = projects + (project.id to project))
            }
            "project.removed" -> {
                val id = (obj["projectId"] as? JsonPrimitive)?.contentOrNull ?: return this
                copy(projects = projects - id)
            }
            "thread.updated" -> {
                val thread = T3Json.decodeFromJsonElement<ThreadShell>(obj["thread"] ?: return this)
                val location = (obj["location"] as? JsonPrimitive)?.contentOrNull
                if (location == "archive" || thread.deletedAt != null) copy(threads = threads - thread.id)
                else copy(threads = threads + (thread.id to thread))
            }
            "thread.removed" -> {
                val id = (obj["threadId"] as? JsonPrimitive)?.contentOrNull ?: return this
                copy(threads = threads - id)
            }
            else -> this
        }
        return next.copy(sequence = seq)
    }
}
