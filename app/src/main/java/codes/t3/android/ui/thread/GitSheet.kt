package codes.t3.android.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.GitAction
import codes.t3.android.data.GitStatus
import codes.t3.android.ui.components.rememberHaptics
import codes.t3.android.ui.home.segmentShape
import codes.t3.android.ui.theme.T3

/** A pull request linked to the thread, for the "Linked pull requests" list. */
data class LinkedPr(val host: String, val repository: String, val number: Int, val url: String, val title: String?, val state: String?)

data class GitUiState(
    val cwd: String? = null,
    val status: GitStatus? = null,
    val loading: Boolean = false,
    /** Label of the git action in progress ("Pushing…"), if any. */
    val busy: String? = null,
    val error: String? = null,
    val links: List<LinkedPr> = emptyList(),
    /** Whether the server can link pull requests to threads. */
    val canLink: Boolean = false,
    val worktreePath: String? = null,
)

class GitCallbacks(
    val onRefresh: () -> Unit = {},
    val onAction: (GitAction, message: String?, featureBranch: Boolean) -> Unit = { _, _, _ -> },
    val onPull: () -> Unit = {},
    val onLink: (String) -> Unit = {},
    val onUnlink: (LinkedPr) -> Unit = {},
)

/**
 * Per-thread git controls, like the upstream mobile app's git overview: status of the thread's checkout, then
 * Commit / Push / Create or View PR / Pull, each explaining why it is unavailable, plus the thread's linked PRs.
 */
@Composable
fun GitSheet(state: GitUiState, callbacks: GitCallbacks, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GitContent(state, callbacks)
    }
}

@Composable
fun GitContent(state: GitUiState, callbacks: GitCallbacks, modifier: Modifier = Modifier) {
    var committing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<GitAction, String?>?>(null) }
    var linking by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val haptics = rememberHaptics()
    val status = state.status
    val idle = state.busy == null
    fun start(action: GitAction, message: String? = null, featureBranch: Boolean = false) {
        // Pushing straight to the default branch gets a second look, as upstream does.
        if (status?.isDefaultRef == true && action.pushes && !featureBranch) confirm = action to message
        else { haptics.confirm(); callbacks.onAction(action, message, featureBranch) }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Git", style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CallSplit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status?.refName ?: if (status == null) "Loading…" else "Detached HEAD",
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status != null && status.isRepo) {
                        Text("  ·  ${status.summary}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (state.loading) LoadingIndicator(Modifier.size(32.dp))
            else IconButton(onClick = { haptics.tick(); callbacks.onRefresh() }) { Icon(Icons.Rounded.Refresh, "Refresh repository status") }
        }
        Spacer(Modifier.height(12.dp))

        state.busy?.let { label ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }

        when {
            status == null -> {}
            !status.isRepo -> Text(
                "Not a git repository. Changes in this thread's folder aren't tracked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            else -> {
                val rows = buildList {
                    add(GitRow(Icons.Rounded.Commit, "Commit", status.commitBlocked ?: changedLabel(status), status.commitBlocked == null) { committing = true })
                    add(GitRow(Icons.Rounded.CloudUpload, "Push", status.pushBlocked ?: "${status.aheadCount} ${if (status.aheadCount == 1) "commit" else "commits"} ahead", status.pushBlocked == null) { start(GitAction.Push) })
                    val pr = status.openPr
                    if (pr != null) add(GitRow(Icons.Rounded.OpenInNew, "View PR", "PR #${pr.number} open · ${pr.title}", true) { uri.openUri(pr.url) })
                    else add(GitRow(Icons.Rounded.Add, "Create PR", status.createPrBlocked ?: "Push and open a pull request", status.createPrBlocked == null) { start(GitAction.CreatePr) })
                    if (status.behindCount > 0) add(GitRow(Icons.Rounded.Download, "Pull latest", "${status.behindCount} behind upstream", true) { haptics.confirm(); callbacks.onPull() })
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEachIndexed { i, row -> GitActionRow(row, segmentShape(i, rows.size), idle) }
                }
                if (status.workingTree.files.isNotEmpty()) {
                    SheetHeader("Changes")
                    status.workingTree.files.take(30).forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            DiffStat(f.insertions, f.deletions)
                        }
                    }
                    if (status.workingTree.files.size > 30) {
                        Text("and ${status.workingTree.files.size - 30} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }

        if (state.links.isNotEmpty() || state.canLink) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Linked pull requests", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (state.canLink) TextButton(onClick = { linking = true }) { Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Link") }
            }
            if (state.links.isEmpty()) {
                Text("None yet. PRs this thread creates are linked automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            }
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.links.forEachIndexed { i, link ->
                    Surface(onClick = { uri.openUri(link.url) }, shape = segmentShape(i, state.links.size), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("#${link.number} ${link.title ?: "Pull request"}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${link.repository} · ${link.state?.replaceFirstChar { it.uppercase() } ?: "Status pending"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.canLink) IconButton(onClick = { haptics.reject(); callbacks.onUnlink(link) }) { Icon(Icons.Rounded.LinkOff, "Unlink #${link.number}") }
                        }
                    }
                }
            }
        }
        state.worktreePath?.let {
            SheetHeader("Worktree")
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }

    if (committing && status != null) {
        CommitDialog(status, onDismiss = { committing = false }) { action, message, branch -> committing = false; start(action, message, branch) }
    }
    confirm?.let { (action, message) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Warning, null, tint = T3.colors.warning) },
            title = { Text("Push to ${status?.refName ?: "the default branch"}?") },
            text = { Text("This is the repository's default branch. Commit on a new branch instead to keep it clean.") },
            confirmButton = {
                if (action.commits) TextButton(onClick = { confirm = null; haptics.confirm(); callbacks.onAction(action, message, true) }) { Text("New branch") }
                TextButton(onClick = { confirm = null; haptics.confirm(); callbacks.onAction(action, message, false) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    if (linking) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linking = false },
            icon = { Icon(Icons.Rounded.Link, null) },
            title = { Text("Link pull request") },
            text = {
                OutlinedTextField(url, { url = it }, label = { Text("Pull request URL") }, placeholder = { Text("https://github.com/owner/repo/pull/42") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            },
            confirmButton = { TextButton(enabled = codes.t3.android.data.Git.parsePrUrl(url) != null, onClick = { linking = false; callbacks.onLink(url.trim()) }) { Text("Link") } },
            dismissButton = { TextButton(onClick = { linking = false }) { Text("Cancel") } },
        )
    }
}

private fun changedLabel(s: GitStatus): String =
    "${s.workingTree.files.size} ${if (s.workingTree.files.size == 1) "file" else "files"} changed · +${s.workingTree.insertions} −${s.workingTree.deletions}"

private data class GitRow(val icon: ImageVector, val title: String, val subtitle: String, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun GitActionRow(row: GitRow, shape: RoundedCornerShape, idle: Boolean) {
    val enabled = row.enabled && idle
    Surface(onClick = row.onClick, enabled = enabled, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
            Icon(row.icon, null, Modifier.size(22.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp))
}

/** Commit message (blank = generated by the server), what to do after committing, and whether to branch first. */
@Composable
private fun CommitDialog(status: GitStatus, onDismiss: () -> Unit, onCommit: (GitAction, String?, Boolean) -> Unit) {
    var message by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf(status.isDefaultRef) }
    val best = status.bestCommitAction
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Commit, null) },
        title = { Text("Commit ${status.workingTree.files.size} ${if (status.workingTree.files.size == 1) "file" else "files"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    message, { message = it },
                    label = { Text("Message") },
                    placeholder = { Text("Leave empty to auto-generate") },
                    minLines = 2,
                    shape = RoundedCornerShape(16.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(newBranch, { newBranch = it })
                    Text("Commit on a new branch", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Box {
                Row {
                    if (best != GitAction.Commit) TextButton(onClick = { onCommit(GitAction.Commit, message, newBranch) }) { Text("Commit") }
                    TextButton(onClick = { onCommit(best, message, newBranch) }) { Text(best.label) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
