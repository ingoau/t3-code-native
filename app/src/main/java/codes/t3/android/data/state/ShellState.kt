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
    /** Orchestration protocol of the server (1 = t3 ≤ 0.0.45, 2 = current). */
    val protocol: Int = 2,
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
        if (protocol == 1) return applyV1(obj, kind)
        if (kind == "snapshot") {
            val snapshot = T3Json.decodeFromJsonElement<ShellSnapshot>(obj["snapshot"] ?: return this)
            // A late snapshot carrying resolvedRepositoryIdentityRoots is only an enrichment refresh.
            if (obj.containsKey("resolvedRepositoryIdentityRoots") && loaded) {
                return copy(projects = projects + snapshot.projects.associateBy { it.id })
            }
            return ShellState(
                protocol = protocol,
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

    private fun applyV1(obj: JsonObject, kind: String?): ShellState {
        if (kind == "snapshot") {
            val snap = obj["snapshot"] as? JsonObject ?: return this
            val projects = (snap["projects"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { T3Json.decodeFromJsonElement<ProjectShell>(it) }
            val threads = (snap["threads"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { codes.t3.android.data.v1.V1.threadShell(it) }
            return ShellState(
                protocol = 1,
                sequence = (snap["snapshotSequence"] as? JsonPrimitive)?.longOrNull ?: 0,
                projects = projects.associateBy { it.id },
                threads = threads.filter { it.archivedAt == null && it.deletedAt == null }.associateBy { it.id },
                synchronized = synchronized,
            )
        }
        if (kind == "synchronized") return copy(synchronized = true)
        val seq = (obj["sequence"] as? JsonPrimitive)?.longOrNull ?: return this
        if (seq <= sequence) return this
        val next = when (kind) {
            "project-upserted" -> {
                val project = T3Json.decodeFromJsonElement<ProjectShell>(obj["project"] ?: return this)
                copy(projects = projects + (project.id to project))
            }
            "project-removed" -> copy(projects = projects - ((obj["projectId"] as? JsonPrimitive)?.contentOrNull ?: return this))
            "thread-upserted" -> {
                val thread = codes.t3.android.data.v1.V1.threadShell(obj["thread"] as? JsonObject ?: return this)
                if (thread.archivedAt != null || thread.deletedAt != null) copy(threads = threads - thread.id)
                else copy(threads = threads + (thread.id to thread))
            }
            "thread-removed" -> copy(threads = threads - ((obj["threadId"] as? JsonPrimitive)?.contentOrNull ?: return this))
            else -> this
        }
        return next.copy(sequence = seq)
    }
}
