package codes.t3.android.ui.environments

import codes.t3.android.ui.components.rememberHaptics
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.EnvironmentSnapshot
import codes.t3.android.ui.components.EmptyState
import codes.t3.android.ui.home.segmentShape
import codes.t3.android.ui.theme.T3

@Composable
fun EnvironmentsScreen(
    environments: List<EnvironmentSnapshot>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onReconnect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by remember { mutableStateOf<EnvironmentSnapshot?>(null) }
    var removing by remember { mutableStateOf<EnvironmentSnapshot?>(null) }
    val haptics = rememberHaptics()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Environments") },
                subtitle = { Text("Machines running T3 Code that this phone can control") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add environment") })
        },
    ) { padding ->
        if (environments.isEmpty()) {
            EmptyState(
                Icons.Rounded.Computer,
                "No environments yet",
                "Run `t3 pair` on the machine you want to control, then scan the QR code it prints.",
                actionLabel = "Add environment",
                onAction = onAdd,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 120.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(environments, key = { _, e -> e.saved.environmentId }) { index, env ->
                EnvironmentCard(
                    env,
                    shape = segmentShape(index, environments.size),
                    onToggle = { haptics.toggle(it); onToggle(env.saved.environmentId, it) },
                    onRename = { renaming = env },
                    onReconnect = { onReconnect(env.saved.environmentId) },
                    onRemove = { removing = env },
                )
            }
        }
    }
    renaming?.let { env ->
        var label by remember { mutableStateOf(env.saved.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename environment") },
            text = { OutlinedTextField(label, { label = it }, singleLine = true, placeholder = { Text("My MacBook") }, shape = RoundedCornerShape(16.dp)) },
            confirmButton = { TextButton(onClick = { onRename(env.saved.environmentId, label); renaming = null }, enabled = label.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { env ->
        AlertDialog(
            onDismissRequest = { removing = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Remove ${env.saved.label}?") },
            text = { Text("This phone will forget the environment and its access token. Threads on the machine are not affected; you can pair again any time.") },
            confirmButton = { TextButton(onClick = { onRemove(env.saved.environmentId); removing = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

data class StatusTone(val label: String, val color: Color)

@Composable
fun statusTone(env: EnvironmentSnapshot): StatusTone = if (!env.saved.enabled) StatusTone("Off", MaterialTheme.colorScheme.outline) else when (env.status) {
    ConnectionStatus.Connected -> StatusTone("Connected", T3.colors.success)
    ConnectionStatus.Connecting -> StatusTone("Connecting", T3.colors.info)
    is ConnectionStatus.Reconnecting -> StatusTone("Reconnecting", T3.colors.warning)
    is ConnectionStatus.Blocked -> StatusTone("Connection failed", MaterialTheme.colorScheme.error)
    ConnectionStatus.Disabled -> StatusTone("Off", MaterialTheme.colorScheme.outline)
}

@Composable
private fun EnvironmentCard(
    env: EnvironmentSnapshot,
    shape: RoundedCornerShape,
    onToggle: (Boolean) -> Unit,
    onRename: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val tone = statusTone(env)
    val machine = env.config?.environment?.platform?.machine
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, onClick = { expanded = !expanded }) {
        Column(Modifier.fillMaxWidth().padding(18.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        when (machine) { "laptop" -> Icons.Rounded.Laptop; "server", "cloud" -> Icons.Rounded.Storage; else -> Icons.Rounded.Computer },
                        null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(env.saved.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(env.saved.displayHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.size(8.dp).background(tone.color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(tone.label, style = MaterialTheme.typography.labelMedium, color = tone.color)
                        env.config?.environment?.serverVersion?.let {
                            Text("  ·  v$it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Switch(checked = env.saved.enabled, onCheckedChange = onToggle)
            }
            val error = (env.status as? ConnectionStatus.Blocked)?.error ?: (env.status as? ConnectionStatus.Reconnecting)?.error
            if (error != null && env.saved.enabled) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
            }
            if (expanded) {
                val providers = env.config?.providers?.filter { it.selectable }.orEmpty()
                if (providers.isNotEmpty()) {
                    Text(
                        "Providers: " + providers.joinToString { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRename) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Rename") }
                    OutlinedButton(onClick = onReconnect, enabled = env.saved.enabled) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reconnect") }
                    TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

