package dev.codexpocket

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Observe after children: code, tables, images and text selection retain their gestures. */
internal fun Modifier.sessionSwipe(key: Any?, switch: (Int) -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var dx = 0f; var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.count { it.pressed } > 1) break
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed || change.uptimeMillis - down.uptimeMillis > 700) break
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            if (abs(dy) > 12.dp.toPx() && abs(dy) > abs(dx)) break
            // Clickable rows consume down. Claim horizontal movement after children have
            // had the chance to scroll, cancelling the row click without stealing code/image drags.
            if (change.pressed && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.5f) change.consume()
            if (!change.pressed) {
                if (abs(dx) >= 64.dp.toPx() && abs(dx) > abs(dy) * 1.5f) switch(if (dx < 0) 1 else -1)
                break
            }
        }
    }
}
