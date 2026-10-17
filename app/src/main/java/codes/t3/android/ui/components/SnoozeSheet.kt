package codes.t3.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import codes.t3.android.ui.home.segmentShape
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class SnoozePreset(val id: String, val label: String, val whenLabel: String, val until: Instant)

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/**
 * Upstream `resolveSnoozePresets`: in 1 hour, in 3 hours, this evening (18:00, if more than an hour away),
 * tomorrow 09:00 and next Monday 09:00 (unless that is tomorrow).
 */
fun snoozePresets(now: ZonedDateTime): List<SnoozePreset> = buildList {
    fun clock(t: ZonedDateTime) = t.format(timeFormat)
    val hour = now.plusHours(1)
    add(SnoozePreset("hour", "In 1 hour", clock(hour), hour.toInstant()))
    val three = now.plusHours(3)
    add(SnoozePreset("three-hours", "In 3 hours", clock(three), three.toInstant()))
    val evening = now.toLocalDate().atTime(18, 0).atZone(now.zone)
    if (evening.isAfter(now.plusHours(1))) add(SnoozePreset("evening", "This evening", clock(evening), evening.toInstant()))
    val tomorrow = now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone)
    add(SnoozePreset("tomorrow", "Tomorrow", clock(tomorrow), tomorrow.toInstant()))
    val monday = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0).atZone(now.zone)
    if (monday != tomorrow) {
        add(SnoozePreset("next-week", "Next week", monday.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + clock(monday), monday.toInstant()))
    }
}

/**
 * Snooze a thread: it leaves the inbox until [SnoozePreset.until], then comes back to Active. Presets match the
 * upstream apps; "Pick date & time" covers everything else.
 */
@Composable
fun SnoozeSheet(onSnooze: (Instant) -> Unit, onDismiss: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    var custom by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Text("Snooze until…", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
            val presets = remember(now) { snoozePresets(now) }
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                (presets.map { it to Icons.Rounded.Snooze } + (null to Icons.Rounded.EditCalendar)).forEachIndexed { i, (preset, icon) ->
                    Surface(
                        onClick = { haptics.confirm(); if (preset != null) onSnooze(preset.until) else custom = true },
                        shape = segmentShape(i, presets.size + 1),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Text(preset?.label ?: "Pick date & time", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (preset != null) Text(preset.whenLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (custom) CustomSnoozeDialogs(now, onSnooze = onSnooze, onDismiss = { custom = false })
}

/** Date, then time; the result must be in the future. */
@Composable
private fun CustomSnoozeDialogs(now: ZonedDateTime, onSnooze: (Instant) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val default = now.plusHours(1)
    if (date == null) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = default.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    date = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(picker) }
    } else {
        val time = rememberTimePickerState(default.hour, default.minute)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Wake at") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(time)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val wake = resolveCustomSnooze(date!!, LocalTime.of(time.hour, time.minute), now.zone, now.toInstant())
                    if (wake == null) error = "Choose a date and time in the future." else onSnooze(wake)
                }) { Text("Snooze") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            shape = RoundedCornerShape(28.dp),
        )
    }
}

/** Null when the local time doesn't exist (DST gap) or isn't in the future, as upstream `resolveCustomSnooze`. */
fun resolveCustomSnooze(date: LocalDate, time: LocalTime, zone: ZoneId, now: Instant): Instant? {
    val local = date.atTime(time)
    if (zone.rules.getValidOffsets(local).isEmpty()) return null
    return local.atZone(zone).toInstant().takeIf { it.isAfter(now) }
}
