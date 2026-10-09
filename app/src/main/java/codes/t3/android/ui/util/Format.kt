package codes.t3.android.ui.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** `<1m`, `5m`, `3h`, `2d`, `6w` — compact age used in list rows. */
fun relativeAge(iso: String?, now: Instant = Instant.now()): String {
    val then = parseInstant(iso) ?: return ""
    val d = Duration.between(then, now).coerceAtLeast(Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        d.toDays() < 14 -> "${d.toDays()}d"
        else -> "${d.toDays() / 7}w"
    }
}

/** `12s`, `3m 04s`, `1h 2m`. */
fun formatElapsed(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${"%02d".format(Locale.ROOT, s % 60)}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

fun clockTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    parseInstant(iso)?.atZone(zone)?.format(clock) ?: ""

fun Duration.coerceAtLeast(min: Duration): Duration = if (this < min) min else this
