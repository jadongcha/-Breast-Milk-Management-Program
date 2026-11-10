package app.milkbank.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Resets the idle timer. Provided by MainActivity; dialogs and popups inherit it. */
val LocalIdleTouch = compositionLocalOf<() -> Unit> { {} }

/**
 * Reports every pointer event (before children consume it) and hardware key event in this
 * subtree to the idle timer. Needed in windows other than the activity window (dialogs, popups).
 */
fun Modifier.idleTouch(): Modifier = composed {
    val touch by rememberUpdatedState(LocalIdleTouch.current)
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    touch()
                }
            }
        }
        .onPreviewKeyEvent {
            touch()
            false
        }
}

/** Drop-in [Dialog] whose content counts as user activity. */
@Composable
fun IdleAwareDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Box(Modifier.idleTouch()) { content() }
    }
}
