package app.muse.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** Claims only a vertical drag in the requested direction; taps and horizontal swipes pass through. */
@Composable
internal fun playerSwipeModifier(
    upwards: Boolean,
    onStart: () -> Boolean,
    onDelta: (Float) -> Unit,
    onFinish: (velocityY: Float, cancelled: Boolean) -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val update by rememberUpdatedState(onDelta)
    val finish by rememberUpdatedState(onFinish)
    return Modifier.pointerInput(upwards) {
        val direction = if (upwards) -1f else 1f
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, Offset.Zero)
            var total = Offset.Zero
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break

                    val delta = change.positionChange()
                    // The visual pane moves with the finger. Track event deltas rather than
                    // raw local positions, which shift with the pane's coordinate origin.
                    total += delta
                    velocity.addPosition(change.uptimeMillis, Offset(0f, total.y))
                    if (!change.pressed) {
                        if (dragging) {
                            change.consume()
                            dragging = false
                            finish(velocity.calculateVelocity().y, false)
                        }
                        break
                    }
                    if (!dragging) {
                        if (abs(total.x) > slop && abs(total.x) >= abs(total.y)) break
                        if (abs(total.y) <= slop || abs(total.y) <= abs(total.x)) continue
                        if (total.y * direction <= 0f || !start()) break
                        dragging = true
                        update(total.y - direction * slop)
                    } else {
                        update(delta.y)
                    }
                    change.consume()
                }
            } finally {
                // Consumption by another recognizer, multitouch and modifier disposal cancel.
                if (dragging) finish(0f, true)
            }
        }
    }
}
