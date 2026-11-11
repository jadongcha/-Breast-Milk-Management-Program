package app.milkbank.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val MinButton = Modifier.heightIn(min = 56.dp)

/** Register / edit form. The baby name is shown here and nowhere else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientFormScreen(viewModel: PatientFormViewModel, editing: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (editing) R.string.patient_form_edit_title else R.string.patient_form_new_title))
                },
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
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val enabled = !loading && !submitting
            val fieldModifier = Modifier.fillMaxWidth()
            IdleTextField(form.regNo, viewModel::setRegNo, stringResource(R.string.field_reg_no), fieldModifier, enabled = enabled)
            IdleTextField(form.babyName, viewModel::setBabyName, stringResource(R.string.field_baby_name), fieldModifier, enabled = enabled)
            IdleTextField(form.bed, viewModel::setBed, stringResource(R.string.field_bed), fieldModifier, enabled = enabled)
            IdleTextField(
                form.guardianName, viewModel::setGuardianName, stringResource(R.string.field_guardian_name),
                fieldModifier, enabled = enabled,
            )
            IdleTextField(
                form.guardianPhone, viewModel::setGuardianPhone, stringResource(R.string.field_guardian_phone),
                fieldModifier, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedButton(
                onClick = { pickingDate = true }, enabled = enabled,
                modifier = fieldModifier.then(MinButton),
            ) { Text(stringResource(R.string.field_admitted_on) + "  " + form.admittedOn) }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }

            Button(
                onClick = viewModel::submit,
                enabled = enabled && online,
                modifier = fieldModifier.then(MinButton),
            ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleLarge) }
        }
    }

    if (pickingDate) {
        AdmittedOnPicker(
            current = form.admittedOn,
            onPick = { viewModel.setAdmittedOn(it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Material3 DatePicker works in UTC midnights; the picked calendar date is read back in UTC, so it is zone-independent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdmittedOnPicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = runCatching { LocalDate.parse(current) }.getOrNull()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    IdleAwareDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                DatePicker(state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = MinButton) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let {
                                onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            }
                        },
                        enabled = state.selectedDateMillis != null,
                        modifier = MinButton,
                    ) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}
