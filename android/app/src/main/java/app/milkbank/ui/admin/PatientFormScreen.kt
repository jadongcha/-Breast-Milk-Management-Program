package app.milkbank.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.components.IdleTextField
import app.milkbank.ui.patients.DISCARD_REASONS
import app.milkbank.ui.patients.DialogShell
import app.milkbank.ui.patients.reasonLabel
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
    val erased by viewModel.erased.collectAsStateWithLifecycle()
    val canDischarge by viewModel.canDischarge.collectAsStateWithLifecycle()
    val canErase by viewModel.canErase.collectAsStateWithLifecycle()
    val loadedRegNo by viewModel.loadedRegNo.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val dischargeDialog by viewModel.dischargeDialog.collectAsStateWithLifecycle()
    val eraseDialog by viewModel.eraseDialog.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onDone() }

    Scaffold(

        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
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
            val enabled = !loading && !submitting && !erased
            val fieldModifier = Modifier.fillMaxWidth()
            if (erased) {
                Text(
                    stringResource(R.string.erased_badge),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

            if (!erased) {
                Button(
                    onClick = viewModel::submit,
                    enabled = enabled && online,
                    modifier = fieldModifier.then(MinButton),
                ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleLarge) }
            }
            if (canDischarge) {
                OutlinedButton(
                    onClick = viewModel::openDischarge, enabled = enabled && online,
                    modifier = fieldModifier.then(MinButton),
                ) { Text(stringResource(R.string.discharge)) }
            }
            if (canErase) {
                OutlinedButton(
                    onClick = viewModel::openErase, enabled = enabled && online,
                    modifier = fieldModifier.then(MinButton),
                ) { Text(stringResource(R.string.erase_now), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (dischargeDialog) {
        DischargeDialog(
            regNo = loadedRegNo, balance = balance, submitting = submitting, online = online,
            onConfirm = viewModel::discharge, onDismiss = viewModel::closeDischarge,
        )
    }
    if (eraseDialog) {
        EraseDialog(
            regNo = loadedRegNo, submitting = submitting, online = online,
            onConfirm = viewModel::erase, onDismiss = viewModel::closeErase,
        )
    }

    if (pickingDate) {
        AdmittedOnPicker(
            current = form.admittedOn,
            onPick = { viewModel.setAdmittedOn(it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Also used by the daily-usage screen for any date. Material3 DatePicker works in UTC midnights; the picked calendar date is read back in UTC, so it is zone-independent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdmittedOnPicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
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

@Composable
private fun BabyIdHeading(regNo: String) {
    Text(
        regNo,
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ConfirmButtons(submitting: Boolean, online: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.weight(1f).then(MinButton)) {
            Text(stringResource(R.string.cancel))
        }
        Button(onClick = onConfirm, enabled = !submitting && online, modifier = Modifier.weight(1f).then(MinButton)) {
            Text(stringResource(R.string.confirm))
        }
    }
}

/** Step 1 (only when packs remain): return or discard (with reason). Step 2: the baby ID shown large to confirm. */
@Composable
private fun DischargeDialog(
    regNo: String,
    balance: Int,
    submitting: Boolean,
    online: Boolean,
    onConfirm: (remaining: String, discardReason: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var remaining by rememberSaveable { mutableStateOf("return") }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(balance == 0) }
    DialogShell(onDismiss, dismissable = !submitting) {
        BabyIdHeading(regNo)
        if (!confirming) {
            for (choice in listOf("return" to R.string.discharge_return, "discard" to R.string.discharge_discard)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = remaining == choice.first, role = Role.RadioButton, onClick = { remaining = choice.first }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = remaining == choice.first, onClick = null)
                    Text(stringResource(choice.second), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (remaining == "discard") {
                Text(stringResource(R.string.discard_reason_label), style = MaterialTheme.typography.titleMedium)
                for (r in DISCARD_REASONS) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = reason == r, role = Role.RadioButton, onClick = { reason = r }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(reasonLabel(r), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).then(MinButton)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { confirming = true },
                    enabled = remaining == "return" || reason != null,
                    modifier = Modifier.weight(1f).then(MinButton),
                ) { Text(stringResource(R.string.next)) }
            }
        } else {
            val text = if (balance == 0) {
                stringResource(R.string.discharge_confirm_empty, regNo)
            } else {
                val target = stringResource(if (remaining == "discard") R.string.discharge_discard else R.string.discharge_return)
                stringResource(R.string.discharge_confirm, regNo, balance, target)
            }
            Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (balance > 0 && remaining == "discard") {
                reason?.let { Text(reasonLabel(it), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
            ConfirmButtons(submitting, online, onConfirm = {
                if (balance == 0) onConfirm("return", null)
                else onConfirm(remaining, if (remaining == "discard") reason else null)
            }, onDismiss)
        }
    }
}

@Composable
private fun EraseDialog(regNo: String, submitting: Boolean, online: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogShell(onDismiss, dismissable = !submitting) {
        BabyIdHeading(regNo)
        Text(
            stringResource(R.string.erase_confirm),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        ConfirmButtons(submitting, online, onConfirm, onDismiss)
    }
}
