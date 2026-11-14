package app.milkbank.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.components.IdleTextField

private val MinButton = Modifier.heightIn(min = 56.dp)

private fun SettingKey.labelRes() = when (this) {
    SettingKey.DAYS_THRESHOLD -> R.string.setting_days_threshold
    SettingKey.PACKS_THRESHOLD -> R.string.setting_packs_threshold
    SettingKey.AVG_DAYS -> R.string.setting_avg_days
    SettingKey.STAFF_IDLE_MINUTES -> R.string.setting_staff_idle_minutes
    SettingKey.RETENTION_DAYS -> R.string.setting_retention_days
}

/** Admin settings: five whole-number fields. Range errors from the server are shown as sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val fields by viewModel.fields.collectAsStateWithLifecycle()
    val invalid by viewModel.invalid.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val confirmRetention by viewModel.confirmRetention.collectAsStateWithLifecycle()
    val online = LocalOnline.current

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp).then(MinButton)) {
                        Text(stringResource(R.string.back_with_arrow))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fields.isNotEmpty()) {
                for (key in SettingKey.entries) {
                    Column {
                        IdleTextField(
                            fields[key].orEmpty(), { viewModel.setField(key, it) }, stringResource(key.labelRes()),
                            Modifier.fillMaxWidth(), enabled = !saving, isError = key in invalid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if (key in invalid) {
                            Text(
                                stringResource(R.string.settings_number_error),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Button(
                    onClick = viewModel::save, enabled = online && !saving && invalid.isEmpty(),
                    modifier = Modifier.fillMaxWidth().then(MinButton),
                ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleLarge) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            if (saved) Text(stringResource(R.string.settings_saved), style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (confirmRetention) {
        IdleAwareDialog(
            onDismissRequest = viewModel::dismissRetentionConfirm,
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.settings_retention_warning), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = viewModel::dismissRetentionConfirm, modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = viewModel::confirmRetentionSave, enabled = online && !saving,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.confirm)) }
                    }
                }
            }
        }
    }
}
