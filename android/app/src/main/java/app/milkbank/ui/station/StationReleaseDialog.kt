package app.milkbank.ui.station

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalIdleTouch
import app.milkbank.ui.components.IdleTextField

private val MinButton = Modifier.heightIn(min = 56.dp)

/** Admin sign-in, then release. [onClose] is for cancel/dismiss; a finished release leaves via the station info clearing. */
@Composable
fun StationReleaseDialog(
    viewModel: StationReleaseViewModel,
    stationToken: String,
    stationInvalid: Boolean,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    if (state == ReleaseState.Done) return

    // The dialog's own idle timer (signed in and untouched for a minute closes it): feed it touches and ticks.
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.checkIdle()
            delay(1000)
        }
    }
    if (state == ReleaseState.TimedOut) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val busy = state == ReleaseState.Releasing || (state as? ReleaseState.Login)?.loading == true
    val close = {
        viewModel.cancel()
        onClose()
    }
    val outerTouch = LocalIdleTouch.current
    CompositionLocalProvider(LocalIdleTouch provides { outerTouch(); viewModel.touch() }) {
    IdleAwareDialog(
        onDismissRequest = { if (!busy) close() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = Color.White) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.station_release_title), style = MaterialTheme.typography.titleLarge)
                val s = state
                if (s is ReleaseState.Login) {
                    IdleTextField(
                        username, { username = it }, stringResource(R.string.username_label),
                        Modifier.fillMaxWidth(), enabled = !s.loading,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                    )
                    IdleTextField(
                        password, { password = it }, stringResource(R.string.password_label),
                        Modifier.fillMaxWidth(), enabled = !s.loading, isError = s.error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                        ),
                    )
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = close, enabled = !s.loading,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = {
                                viewModel.login(username, password)
                                password = ""
                            },
                            enabled = !s.loading && username.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) {
                            if (s.loading) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.login_button))
                        }
                    }
                } else {
                    (s as? ReleaseState.Error)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = close, enabled = !busy,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = { viewModel.release(stationToken, stationInvalid) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.station_release_button))
                        }
                    }
                }
            }
        }
    }
    }
}
