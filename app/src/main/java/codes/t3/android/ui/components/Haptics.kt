package codes.t3.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Named haptic moments so the whole app feels consistent. */
@Immutable
class Haptics(private val feedback: HapticFeedback) {
    /** Light tick: selecting an option, expanding a row, switching tabs. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    /** Something succeeded: sent, approved, connected, copied. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    /** Something was declined, stopped, or failed. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    /** A gesture crossed its commit threshold (swipe actions, pull to refresh) or something needs attention. */
    fun threshold() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    fun contextClick() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
