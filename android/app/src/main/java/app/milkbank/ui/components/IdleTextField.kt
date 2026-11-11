package app.milkbank.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import app.milkbank.ui.LocalIdleTouch

/**
 * Wraps [onChange] so the idle timer is reset first. Soft-keyboard typing produces no key or
 * touch event the activity sees, so every text change must report activity itself.
 */
fun idleAwareChange(touch: () -> Unit, onChange: (String) -> Unit): (String) -> Unit = { value ->
    touch()
    onChange(value)
}

/** The only text field the app uses: typing counts as activity for the auto-logout timer. */
@Composable
fun IdleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val touch = LocalIdleTouch.current
    OutlinedTextField(
        value = value,
        onValueChange = idleAwareChange(touch, onValueChange),
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        label = { Text(label) },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}
