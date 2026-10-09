package codes.t3.android.ui.thread

import codes.t3.android.ui.components.rememberHaptics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.OptionDescriptor
import codes.t3.android.data.model.ProviderModel
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.data.model.ServerProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Bottom sheet for picking provider + model + model options + runtime mode. */
@Composable
fun ModelPickerSheet(
    providers: List<ServerProvider>,
    selection: ModelSelection?,
    runtimeMode: RuntimeMode,
    onConfirm: (ModelSelection, RuntimeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = rememberHaptics()
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(selection) }
    var runtime by remember { mutableStateOf(runtimeMode) }
    val usable = providers.filter { it.selectable && it.models.isNotEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Model & settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { haptics.confirm(); current?.let { onConfirm(it, runtime) } ?: onDismiss() }, enabled = current != null) { Text("Done") }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find a model") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear model search") } },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (usable.isEmpty()) item {
                Text(
                    "No available models. Set up a provider (Claude Code, Codex, …) on the host machine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            usable.forEach { provider ->
                val models = provider.models.filter { it.isLegacy != true }
                    .filter { query.isBlank() || it.name.contains(query, true) || it.slug.contains(query, true) || provider.label.contains(query, true) }
                if (models.isEmpty()) return@forEach
                item(key = "p-${provider.instanceId}") {
                    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderGlyph(provider.driver, Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(provider.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        provider.auth?.email?.let {
                            Text("  ·  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                items(models, key = { "m-${provider.instanceId}-${it.slug}" }) { model ->
                    val selected = current?.instanceId == provider.instanceId && current?.model == model.slug
                    ModelRow(model, selected) {
                        haptics.tick()
                        current = if (selected) current else ModelSelection(provider.instanceId, model.slug, carryOptions(current, model))
                        if (provider.supportedRuntimeModes != null && runtime.wire !in provider.supportedRuntimeModes) {
                            runtime = RuntimeMode.entries.first { it.wire in provider.supportedRuntimeModes }
                        }
                    }
                }
            }
            val sel = current
            val selModel = sel?.let { s -> usable.firstOrNull { it.instanceId == s.instanceId }?.models?.firstOrNull { it.slug == s.model } }
            val descriptors = selModel?.capabilities?.optionDescriptors.orEmpty().filter { it.type == "select" || it.type == "boolean" }
            if (sel != null && descriptors.isNotEmpty()) {
                item(key = "options-h") { SheetSection("Options") }
                items(descriptors, key = { "o-${it.id}" }) { d ->
                    OptionRow(d, sel) { value ->
                        if (d.type == "boolean") haptics.toggle(value.content == "true") else haptics.tick()
                        current = sel.withOption(d.id, value)
                    }
                }
            }
            item(key = "runtime-h") { SheetSection("Runtime") }
            val provider = usable.firstOrNull { it.instanceId == current?.instanceId }
            items(RuntimeMode.entries.filter { provider?.supportedRuntimeModes == null || it.wire in provider.supportedRuntimeModes }, key = { "r-${it.name}" }) { m ->
                ListItem(
                    modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).clickable { haptics.tick(); runtime = m },
                    leadingContent = { RadioButton(selected = runtime == m, onClick = null) },
                    headlineContent = { Text(m.label) },
                    supportingContent = { Text(m.description) },
                    trailingContent = { Icon(m.icon(), null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}


private fun carryOptions(previous: ModelSelection?, model: ProviderModel) =
    previous?.options?.filter { opt -> model.capabilities?.optionDescriptors?.any { it.id == opt.id } == true }?.ifEmpty { null }

@Composable
private fun SheetSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun ModelRow(model: ProviderModel, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge)
                if (model.name != model.slug) {
                    Text(model.slug, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            model.badge?.let {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun OptionRow(d: OptionDescriptor, selection: ModelSelection, onChange: (JsonPrimitive) -> Unit) {
    val value = selection.option(d.id)
    when (d.type) {
        "boolean" -> {
            val checked = value?.booleanOrNull ?: (d.currentValue as? JsonPrimitive)?.booleanOrNull ?: false
            ListItem(
                modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).clickable { onChange(JsonPrimitive(!checked)) },
                headlineContent = { Text(d.label.ifBlank { d.id }) },
                supportingContent = d.description?.let { { Text(it) } },
                trailingContent = { Switch(checked = checked, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        else -> {
            val chosen = value?.contentOrNull ?: (d.currentValue as? JsonPrimitive)?.contentOrNull ?: d.options.firstOrNull { it.isDefault == true }?.id
            Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
                Text(d.label.ifBlank { d.id }, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    d.options.forEachIndexed { i, opt ->
                        ToggleButton(
                            checked = chosen == opt.id,
                            onCheckedChange = { onChange(JsonPrimitive(opt.id)) },
                            shapes = when {
                                d.options.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                i == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                i == d.options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        ) { Text(opt.label.ifBlank { opt.id }) }
                    }
                }
            }
        }
    }
}
