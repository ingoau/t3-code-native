package codes.t3.android.ui.thread

import codes.t3.android.ui.components.rememberHaptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LiveHelp
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.t3.android.data.model.PendingApproval
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.model.PendingUserInput
import codes.t3.android.data.model.UserInputQuestion
import codes.t3.android.ui.theme.MonoStyle
import codes.t3.android.ui.theme.T3

@Composable
private fun Overline(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp),
        color = color,
    )
}

@Composable
fun ApprovalCard(approval: PendingApproval, onDecision: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    // Nudge when an approval shows up, and confirm/reject on the decision.
    androidx.compose.runtime.LaunchedEffect(approval.requestId) { haptics.threshold() }
    val decide: (String) -> Unit = { d -> if (d == "decline" || d == "cancel") haptics.reject() else haptics.confirm(); onDecision(d) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = T3.colors.warningContainer,
        contentColor = T3.colors.onWarningContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = T3.colors.warning)
                Spacer(Modifier.width(8.dp))
                Overline("Approval needed", T3.colors.warning)
            }
            Spacer(Modifier.size(6.dp))
            Text(approval.title, style = MaterialTheme.typography.titleLarge)
            approval.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(8.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
                    Text(
                        it,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
            if (approval.stale) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "The provider process for this request is no longer available. Interrupt or restart the run to continue.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            approval.options.firstNotNullOfOrNull { it.warning }?.let {
                Spacer(Modifier.size(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                approval.options.forEach { option ->
                    when (option.decision) {
                        "accept" -> Button(onClick = { decide(option.decision) }, enabled = !approval.stale) { Text(option.label) }
                        "decline", "cancel" -> TextButton(onClick = { decide(option.decision) }, enabled = !approval.stale) {
                            Text(option.label, color = MaterialTheme.colorScheme.error)
                        }
                        else -> OutlinedButton(onClick = { decide(option.decision) }, enabled = !approval.stale) { Text(option.label) }
                    }
                }
            }
        }
    }
}

/** Answers keyed by question id. Selected option values plus an optional custom answer. */
@Composable
fun UserInputCard(
    input: PendingUserInput,
    onSubmit: (Map<String, List<String>>, Set<String>) -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    androidx.compose.runtime.LaunchedEffect(input.requestId) { haptics.threshold() }
    val selections = remember(input.requestId) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(input.requestId) { mutableStateMapOf<String, String>() }
    fun answerFor(q: UserInputQuestion): List<String> =
        custom[q.id]?.takeIf { it.isNotBlank() && q.allowCustomAnswer != false }?.let { listOf(it) }
            ?: selections[q.id].orEmpty().toList()
    val complete = input.questions.all { q -> q.required == false || answerFor(q).isNotEmpty() }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LiveHelp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Overline("User input needed", MaterialTheme.colorScheme.tertiary)
            }
            input.questions.forEach { q ->
                Spacer(Modifier.size(12.dp))
                q.header?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(q.question, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(6.dp))
                q.options.forEach { opt ->
                    val value = opt.value ?: opt.label
                    val chosen = value in selections[q.id].orEmpty()
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (chosen) MaterialTheme.colorScheme.surface.copy(alpha = 0.75f) else androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    ) {
                        Row(
                            Modifier
                                .selectable(chosen, role = if (q.multiSelect == true) Role.Checkbox else Role.RadioButton) {
                                    val cur = selections[q.id].orEmpty()
                                    selections[q.id] = if (q.multiSelect == true) (if (chosen) cur - value else cur + value) else setOf(value)
                                    custom.remove(q.id)
                                    haptics.tick()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (q.multiSelect == true) Checkbox(checked = chosen, onCheckedChange = null)
                            else RadioButton(selected = chosen, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                                opt.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                if (q.allowCustomAnswer != false) {
                    OutlinedTextField(
                        value = custom[q.id].orEmpty(),
                        onValueChange = { custom[q.id] = it },
                        placeholder = { Text("Or type a custom answer") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDismiss != null) TextButton(onClick = { haptics.reject(); onDismiss() }) { Text("Dismiss") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = complete && !input.stale,
                    onClick = {
                        haptics.confirm()
                        val answers = input.questions.associate { it.id to answerFor(it) }
                        val multi = input.questions.filter { it.multiSelect == true && custom[it.id].isNullOrBlank() }.map { it.id }.toSet()
                        onSubmit(answers, multi)
                    },
                ) { Text("Submit answers") }
            }
        }
    }
}

/**
 * An agent asking for a secret (an API key, a token). The value is saved in the host's secret store and never shown
 * to the agent or written to the transcript, as on the web.
 */
@Composable
fun SecretRequestCard(item: TurnItem, onSave: (String) -> Unit, onDecline: () -> Unit) {
    var secret by remember(item.id) { mutableStateOf("") }
    var visible by remember(item.id) { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(item.str("label") ?: "Secret requested", style = MaterialTheme.typography.titleMedium)
            }
            item.str("reason")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                placeholder = { Text(item.str("placeholder") ?: "Paste the secret") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (visible) "Hide" else "Show",
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Stored securely, never shown to the agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { haptics.reject(); onDecline() }) { Text("Decline") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = secret.isNotBlank(), onClick = { haptics.confirm(); onSave(secret) }) { Text("Save securely") }
            }
        }
    }
}
