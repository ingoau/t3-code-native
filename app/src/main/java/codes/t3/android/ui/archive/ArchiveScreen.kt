package codes.t3.android.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.ThreadEntry
import codes.t3.android.ui.components.EmptyState
import codes.t3.android.ui.home.segmentShape
import codes.t3.android.ui.util.relativeAge

@Composable
fun ArchiveScreen(
    threads: List<ThreadEntry>?,
    onBack: () -> Unit,
    onUnarchive: (ThreadEntry) -> Unit,
    onDelete: (ThreadEntry) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Archived threads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        when {
            threads == null -> EmptyState(Icons.Rounded.Archive, "Loading archive", "Fetching archived threads…", loading = true, modifier = Modifier.padding(padding))
            threads.isEmpty() -> EmptyState(Icons.Rounded.Archive, "No archived threads", "Threads you archive will appear here.", modifier = Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(threads, key = { _, t -> t.environmentId + t.thread.id }) { i, t ->
                    Surface(shape = segmentShape(i, threads.size), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.project?.title ?: "No project", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(t.thread.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Archived ${relativeAge(t.thread.archivedAt)} ago", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onUnarchive(t) }) { Icon(Icons.Rounded.Unarchive, "Unarchive") }
                            IconButton(onClick = { onDelete(t) }) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}
