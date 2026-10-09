package codes.t3.android.ui.diff

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.ui.components.EmptyState
import codes.t3.android.ui.theme.MonoStyle
import codes.t3.android.ui.theme.T3
import codes.t3.android.ui.thread.DiffStat

data class DiffFile(val path: String, val lines: List<String>, val additions: Int, val deletions: Int, val binary: Boolean)

/** Split a unified `git diff` into per-file sections. */
fun parseUnifiedDiff(diff: String): List<DiffFile> {
    val files = mutableListOf<DiffFile>()
    var path: String? = null
    var lines = mutableListOf<String>()
    var binary = false
    fun flush() {
        val p = path ?: return
        files += DiffFile(p, lines, lines.count { it.startsWith("+") }, lines.count { it.startsWith("-") }, binary)
    }
    for (line in diff.lines()) {
        when {
            line.startsWith("diff --git ") -> {
                flush()
                path = line.substringAfter(" b/", line.removePrefix("diff --git ")).trim()
                lines = mutableListOf()
                binary = false
            }
            path == null -> continue
            line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("index ") ||
                line.startsWith("new file mode") || line.startsWith("deleted file mode") || line.startsWith("similarity index") -> Unit
            line.startsWith("Binary files") -> binary = true
            else -> lines += line
        }
    }
    flush()
    return files.map { f -> f.copy(lines = f.lines.dropLastWhile { it.isEmpty() }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiffScreen(title: String, subtitle: String, diff: String?, error: String?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
    ) { padding ->
        when {
            error != null -> EmptyState(Icons.Rounded.ErrorOutline, "Couldn't load diff", error, modifier = Modifier.padding(padding))
            diff == null -> EmptyState(Icons.Rounded.Difference, "Loading diff…", "Fetching changes from the host.", loading = true, modifier = Modifier.padding(padding))
            diff.isBlank() -> EmptyState(Icons.Rounded.Difference, "No changes", "This turn didn't change any files.", modifier = Modifier.padding(padding))
            else -> {
                val files = parseUnifiedDiff(diff)
                LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
                    item {
                        Text(
                            "${files.size} files · +${files.sumOf { it.additions }} −${files.sumOf { it.deletions }}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                    files.forEach { file ->
                        stickyHeader(key = "h-${file.path}") { FileHeader(file) }
                        item(key = "b-${file.path}") { FileBody(file) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileHeader(file: DiffFile) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(file.path, style = MonoStyle, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
            DiffStat(file.additions, file.deletions)
        }
    }
}

@Composable
private fun FileBody(file: DiffFile) {
    if (file.binary) {
        Text("Binary file", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val added = T3.colors.diffAdded
    val removed = T3.colors.diffRemoved
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = T3.colors.codeBackground,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints {
          val viewport = maxWidth
          Box(Modifier.horizontalScroll(rememberScrollState())) {
            // Every line spans the widest line (and at least the viewport) so highlights form solid bands.
            Column(Modifier.padding(vertical = 8.dp).widthIn(min = viewport).width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                file.lines.forEach { line ->
                    val (bg, fg) = when {
                        line.startsWith("@@") -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) to MaterialTheme.colorScheme.onSecondaryContainer
                        line.startsWith("+") -> added.copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
                        line.startsWith("-") -> removed.copy(alpha = 0.14f) to MaterialTheme.colorScheme.onSurface
                        else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        line.ifEmpty { " " },
                        style = MonoStyle,
                        color = fg,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
          }
        }
    }
}

