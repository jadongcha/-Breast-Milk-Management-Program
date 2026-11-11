package app.milkbank.ui.station

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.components.IdleTextField

private val MinButton = Modifier.heightIn(min = 56.dp)

/** Name entry, then a confirmation; [onDone] runs once the station is saved and the staff session has ended. */
@Composable
fun StationSetupDialog(viewModel: StationSetupViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    var name by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) { if (state == SetupState.Done) onDone() }
    if (state == SetupState.Done) return

    val submitting = state == SetupState.Submitting
    IdleAwareDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = Color.White) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val s = state
                if (s is SetupState.Confirming || s == SetupState.Submitting) {
                    val confirmName = (s as? SetupState.Confirming)?.name ?: name.trim()
                    Text(
                        stringResource(R.string.station_use_confirm, confirmName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = viewModel::reset, enabled = !submitting,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = viewModel::confirm, enabled = online && !submitting,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.confirm)) }
                    }
                } else {
                    Text(stringResource(R.string.station_use_menu), style = MaterialTheme.typography.titleLarge)
                    IdleTextField(
                        name, { name = it }, stringResource(R.string.station_name_label),
                        Modifier.fillMaxWidth(),
                    )
                    (s as? SetupState.Error)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onDismiss, modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = { viewModel.onName(name) },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.confirm)) }
                    }
                }
            }
        }
    }
}
