package com.example.chatlab

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/** How long a finger stays down before a press becomes a hold: the information of a photo, or the removal of a recent search. */
internal const val HOLD_MS = 500L

/**
 * A tap runs [onTap] on release; a finger still down after [HOLD_MS] runs [onHold] (and nothing on release).
 * The finger is read before the layers above it, and nothing is consumed, so a scroll still works.
 */
internal fun Modifier.tapOrHold(key: Any, touchSlop: Float, onTap: () -> Unit, onHold: () -> Unit): Modifier =
    pointerInput(key, touchSlop) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                when (withTimeoutOrNull(HOLD_MS) { trackPress(down, touchSlop) }) {
                    PressOutcome.TAP -> onTap()
                    PressOutcome.MOVED -> Unit
                    null -> {
                        onHold()
                        waitForRelease(down.id)
                    }
                }
            }
        }
    }

/** What a press on a photo turned out to be. */
internal enum class PressOutcome { TAP, MOVED }

/** Follows the press: a release is a tap, a move beyond the touch slop is a scroll. Nothing is consumed. */
internal suspend fun AwaitPointerEventScope.trackPress(down: PointerInputChange, touchSlop: Float): PressOutcome {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: continue
        if (!change.pressed) return PressOutcome.TAP
        if ((change.position - down.position).getDistance() > touchSlop) return PressOutcome.MOVED
    }
}

/** Waits until the finger of [id] is lifted. */
internal suspend fun AwaitPointerEventScope.waitForRelease(id: PointerId) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: continue
        if (!change.pressed) return
    }
}

