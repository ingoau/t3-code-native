package codes.t3.android.ui.thread

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.data.model.ServerProvider

enum class SendMode { Send, Queue, Steer }

data class ComposerModel(
    val providers: List<ServerProvider> = emptyList(),
    val selection: ModelSelection? = null,
    val runtimeMode: RuntimeMode = RuntimeMode.FullAccess,
    val planMode: Boolean = false,
    val running: Boolean = false,
    val canStop: Boolean = false,
    val defaultFollowUp: SendMode = SendMode.Queue,
    val enabled: Boolean = true,
    val placeholder: String = "Ask the agent, or run a command…",
)

fun ComposerModel.provider(): ServerProvider? = providers.firstOrNull { it.instanceId == selection?.instanceId }

fun ComposerModel.modelLabel(): String {
    val p = provider() ?: return selection?.model ?: "Choose model"
    val m = p.models.firstOrNull { it.slug == selection?.model }
    return m?.shortName ?: m?.name ?: selection?.model ?: p.label
}

fun RuntimeMode.icon(): ImageVector = when (this) {
    RuntimeMode.Supervised -> Icons.Rounded.Shield
    RuntimeMode.AutoEdits -> Icons.Rounded.VerifiedUser
    RuntimeMode.Auto -> Icons.Rounded.Gavel
    RuntimeMode.FullAccess -> Icons.Rounded.LockOpen
}

@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    model: ComposerModel,
    onSend: (SendMode) -> Unit,
    onStop: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onTogglePlan: (() -> Unit)?,
    onRuntimeMode: (RuntimeMode) -> Unit,
    enterToSend: Boolean,
    modifier: Modifier = Modifier,
    sendLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    attachments: List<codes.t3.android.ui.app.PendingAttachment> = emptyList(),
    onAddAttachment: (() -> Unit)? = null,
    onRemoveAttachment: (String) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val uploading = attachments.any { it.uploading }
    val canSend = (text.isNotBlank() || attachments.any { it.ref != null }) && model.enabled && !uploading
    val sendMode = if (model.running) model.defaultFollowUp else SendMode.Send
    fun send(mode: SendMode) {
        if (!canSend) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onSend(mode)
    }
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            leading?.invoke()
            if (attachments.isNotEmpty()) AttachmentStrip(attachments, onRemoveAttachment)
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Text(model.placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = model.enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send(sendMode) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp, max = 200.dp)
                        .onPreviewKeyEvent { e ->
                            if (enterToSend && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                                send(sendMode); true
                            } else false
                        },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onAddAttachment != null) {
                        ToolbarChip(Icons.Rounded.Add, null, false, onAddAttachment, contentDescription = "Add image")
                    }
                    ModelPill(model, onOpenModelPicker, Modifier.weight(1f, fill = false))
                    if (onTogglePlan != null) {
                        ToolbarChip(
                            icon = if (model.planMode) Icons.Rounded.Lightbulb else Icons.Rounded.Construction,
                            label = if (model.planMode) "Plan" else "Build",
                            selected = model.planMode,
                            onClick = onTogglePlan,
                        )
                    }
                    RuntimeChip(model.runtimeMode, model.provider()?.supportedRuntimeModes, onRuntimeMode)
                }
                AnimatedVisibility(model.running && model.canStop, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    FilledTonalIconButton(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); onStop() },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(44.dp),
                    ) { Icon(Icons.Rounded.Stop, "Stop agent") }
                }
                SendButton(sendMode, canSend, model.running, sendLabel, onSend = ::send)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SendButton(mode: SendMode, enabled: Boolean, running: Boolean, label: String?, onSend: (SendMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Expressive shape morph: the round button squares off while pressed.
    val corner by androidx.compose.animation.core.animateDpAsState(
        if (pressed) 12.dp else 22.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "send-corner",
    )
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box {
        Surface(
            shape = RoundedCornerShape(corner),
            color = container,
            contentColor = content,
            modifier = Modifier
                .size(width = 52.dp, height = 44.dp)
                .clip(RoundedCornerShape(corner))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = androidx.compose.material3.ripple(),
                    enabled = enabled,
                    onClick = { onSend(mode) },
                    onLongClick = if (running) ({ menu = true }) else null,
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(mode, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "send") { m ->
                    Icon(
                        when (m) {
                            SendMode.Send -> if (label != null) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.ArrowUpward
                            SendMode.Queue -> Icons.Rounded.FormatListNumbered
                            SendMode.Steer -> Icons.Rounded.Bolt
                        },
                        when (m) {
                            SendMode.Send -> label ?: "Send"
                            SendMode.Queue -> "Queue message"
                            SendMode.Steer -> "Steer agent"
                        },
                    )
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(
                text = { Column { Text("Queue"); Text("Run after the current turn", style = MaterialTheme.typography.bodySmall) } },
                leadingIcon = { Icon(Icons.Rounded.FormatListNumbered, null) },
                onClick = { menu = false; onSend(SendMode.Queue) },
            )
            DropdownMenuItem(
                text = { Column { Text("Steer now"); Text("Interrupt what the agent is doing", style = MaterialTheme.typography.bodySmall) } },
                leadingIcon = { Icon(Icons.Rounded.Bolt, null) },
                onClick = { menu = false; onSend(SendMode.Steer) },
            )
        }
    }
}

@Composable
fun ModelPill(model: ComposerModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderGlyph(model.provider()?.driver ?: "", Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                model.modelLabel(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ToolbarChip(icon: ImageVector, label: String?, selected: Boolean, onClick: () -> Unit, contentDescription: String? = null) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = if (label == null) 8.dp else 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription, Modifier.size(18.dp))
            if (label != null) {
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RuntimeChip(mode: RuntimeMode, supported: List<String>?, onChange: (RuntimeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarChip(mode.icon(), null, mode == RuntimeMode.Supervised, { open = true }, contentDescription = "Runtime: ${mode.label}")
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            RuntimeMode.entries.filter { supported == null || it.wire in supported }.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(m.label, style = MaterialTheme.typography.bodyLarge)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(m.icon(), null, tint = if (m == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { open = false; onChange(m) },
                )
            }
        }
    }
}

@Composable
private fun AttachmentStrip(items: List<codes.t3.android.ui.app.PendingAttachment>, onRemove: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        items(items.size, key = { items[it].key }) { i ->
            val a = items[i]
            Box(Modifier.size(72.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(72.dp)) {
                    a.preview?.let {
                        androidx.compose.foundation.Image(
                            it, null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(72.dp),
                            alpha = if (a.uploading) 0.5f else 1f,
                        )
                    }
                }
                if (a.uploading) androidx.compose.material3.LoadingIndicator(Modifier.align(Alignment.Center).size(28.dp))
                Surface(
                    onClick = { onRemove(a.key) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                ) { Icon(Icons.Rounded.Close, "Remove image", Modifier.padding(3.dp)) }
            }
        }
    }
}
