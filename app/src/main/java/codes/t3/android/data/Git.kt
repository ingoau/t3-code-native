package codes.t3.android.data

import codes.t3.android.data.model.T3Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
data class GitFile(val path: String, val insertions: Int = 0, val deletions: Int = 0)

@Serializable
data class GitWorkingTree(val files: List<GitFile> = emptyList(), val insertions: Int = 0, val deletions: Int = 0)

@Serializable
data class GitPullRequest(
    val number: Int,
    val title: String = "",
    val url: String = "",
    val baseRef: String? = null,
    val headRef: String? = null,
    val state: String = "open",
    val isDraft: Boolean? = null,
)

/** `vcs.refreshStatus` result: the local working tree plus the remote side (upstream, ahead/behind, open PR). */
@Serializable
data class GitStatus(
    val isRepo: Boolean = true,
    val hasPrimaryRemote: Boolean = false,
    val isDefaultRef: Boolean = false,
    val refName: String? = null,
    val hasWorkingTreeChanges: Boolean = false,
    val workingTree: GitWorkingTree = GitWorkingTree(),
    val hasUpstream: Boolean = false,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val aheadOfDefaultCount: Int? = null,
    val pr: GitPullRequest? = null,
) {
    val openPr: GitPullRequest? get() = pr?.takeIf { it.state == "open" }
    private val canReachRemote get() = hasUpstream || hasPrimaryRemote

    val summary: String
        get() = buildList {
            val n = workingTree.files.size
            add(if (hasWorkingTreeChanges) (if (n == 1) "1 file changed" else "$n files changed") else "Clean")
            if (aheadCount > 0) add("$aheadCount ahead")
            if (behindCount > 0) add("$behindCount behind")
        }.joinToString(" · ")

    /** Why each action is unavailable (null = available), following upstream `getGitActionDisabledReason`. */
    val commitBlocked: String? get() = if (!hasWorkingTreeChanges) "Worktree is clean. Nothing to commit." else null
    val pushBlocked: String?
        get() = when {
            refName == null -> "Detached HEAD. Check out a branch to push."
            hasWorkingTreeChanges -> "Commit your changes before pushing."
            behindCount > 0 -> "Branch is behind upstream. Pull first."
            aheadCount == 0 -> "No local commits to push."
            !canReachRemote -> "This repository has no remote."
            else -> null
        }
    val createPrBlocked: String?
        get() = when {
            refName == null -> "Detached HEAD. Check out a branch first."
            behindCount > 0 -> "Branch is behind upstream. Pull first."
            (aheadOfDefaultCount ?: aheadCount) == 0 -> "No commits to open a pull request with."
            !canReachRemote -> "This repository has no remote."
            else -> null
        }

    /** The commit action that also takes the work as far as it can go: commit, push, and open a PR when there isn't one. */
    val bestCommitAction: GitAction
        get() = when {
            !canReachRemote -> GitAction.Commit
            openPr != null || isDefaultRef -> GitAction.CommitPush
            else -> GitAction.CommitPushPr
        }

    companion object {
        fun parse(json: JsonObject): GitStatus = T3Json.decodeFromJsonElement(json)
    }
}

/** `git.runStackedAction` action ids. */
enum class GitAction(val wire: String, val label: String, val pushes: Boolean, val commits: Boolean) {
    Commit("commit", "Commit", false, true),
    Push("push", "Push", true, false),
    CreatePr("create_pr", "Create PR", true, false),
    CommitPush("commit_push", "Commit & push", true, true),
    CommitPushPr("commit_push_pr", "Commit, push & PR", true, true),
}

/** One progress event from `git.runStackedAction`, reduced to what the UI shows. */
sealed interface GitProgress {
    data class Phase(val label: String) : GitProgress
    data class Finished(val title: String, val description: String?, val prUrl: String?, val createdBranch: String?) : GitProgress
    data class Failed(val message: String) : GitProgress
}

object Git {
    fun statusRequest(cwd: String) = buildJsonObject { put("cwd", cwd) }

    fun actionRequest(actionId: String, cwd: String, action: GitAction, threadId: String, message: String?, featureBranch: Boolean) = buildJsonObject {
        put("actionId", actionId)
        put("cwd", cwd)
        put("action", action.wire)
        put("threadId", threadId)
        message?.trim()?.takeIf { it.isNotEmpty() }?.let { put("commitMessage", it) }
        if (featureBranch) put("featureBranch", true)
    }

    fun progress(event: JsonObject): GitProgress? {
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        return when (event.s("kind")) {
            "phase_started" -> GitProgress.Phase(event.s("label") ?: "Working…")
            "hook_started" -> GitProgress.Phase("Running ${event.s("hookName") ?: "hook"}…")
            "action_failed" -> GitProgress.Failed(event.s("message") ?: "Git action failed")
            "action_finished" -> {
                val result = event["result"] as? JsonObject
                val toast = result?.get("toast") as? JsonObject
                val cta = toast?.get("cta") as? JsonObject
                val branch = result?.get("branch") as? JsonObject
                GitProgress.Finished(
                    title = toast?.s("title") ?: "Done",
                    description = toast?.s("description"),
                    prUrl = cta?.takeIf { it.s("kind") == "open_pr" }?.s("url") ?: (result?.get("pr") as? JsonObject)?.s("url"),
                    createdBranch = branch?.takeIf { it.s("status") == "created" }?.s("name"),
                )
            }
            else -> null
        }
    }

    /** A pull request URL from GitHub, GitLab, Forgejo/Gitea or Bitbucket: host, `owner/repo` and number. */
    data class PrUrl(val host: String, val repository: String, val number: Int, val url: String)

    fun parsePrUrl(raw: String): PrUrl? {
        val url = raw.trim().removeSuffix("/")
        val m = Regex("""^https?://([^/]+)/(.+?)/(?:-/merge_requests|pull|pulls|pull-requests)/(\d+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).find(url) ?: return null
        val (host, repo, number) = m.destructured
        return PrUrl(host.lowercase(), repo.lowercase(), number.toIntOrNull() ?: return null, url)
    }

    /** `thread.pull-request.link` (multi-link servers) or the legacy single `linkedPullRequest` metadata. */
    fun linkPullRequest(threadId: String, projectId: String, pr: PrUrl, multiple: Boolean) = buildJsonObject {
        put("commandId", Commands.newId())
        put("threadId", threadId)
        if (multiple) {
            put("type", "thread.pull-request.link")
            put("host", pr.host)
            put("repository", pr.repository)
            put("number", pr.number)
            put("url", pr.url)
            put("source", "manual")
        } else {
            put("type", "thread.metadata.update")
            put("linkedPullRequest", buildJsonObject {
                put("projectId", projectId)
                put("repository", pr.repository)
                put("number", pr.number)
                put("url", pr.url)
            })
        }
    }

    fun unlinkPullRequest(threadId: String, host: String, repository: String, number: Int, multiple: Boolean) = buildJsonObject {
        put("commandId", Commands.newId())
        put("threadId", threadId)
        if (multiple) {
            put("type", "thread.pull-request.unlink")
            put("host", host)
            put("repository", repository)
            put("number", number)
        } else {
            put("type", "thread.metadata.update")
            put("linkedPullRequest", kotlinx.serialization.json.JsonNull)
        }
    }
}
