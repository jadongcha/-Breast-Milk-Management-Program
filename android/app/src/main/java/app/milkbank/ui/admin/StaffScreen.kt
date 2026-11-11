package app.milkbank.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.api.StaffDto
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.MilkbankColors
import app.milkbank.ui.components.IdleTextField
import app.milkbank.ui.patients.MilkCard

private val MinButton = Modifier.heightIn(min = 56.dp)

@Composable
private fun roleLabel(role: String) =
    stringResource(if (role == "admin") R.string.role_admin else R.string.role_nurse)

@Composable
private fun DialogSurface(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/** Staff list: username, name, role, active state. Baby names never appear here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffScreen(viewModel: StaffViewModel, onBack: () -> Unit) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val temp by viewModel.tempPassword.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val addOpen by viewModel.addOpen.collectAsStateWithLifecycle()
    val online = LocalOnline.current

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.staff_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp).then(MinButton)) {
                        Text(stringResource(R.string.back_with_arrow))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = viewModel::openAdd, enabled = online && !submitting,
                modifier = Modifier.fillMaxWidth().then(MinButton),
            ) { Text(stringResource(R.string.staff_add), style = MaterialTheme.typography.titleLarge) }
            // While the add dialog is open its own error line shows the message.
            if (!addOpen) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(list, key = { it.id }) { s -> StaffRow(s, online && !submitting, viewModel) }
            }
        }
    }

    pending?.let { p ->
        IdleAwareDialog(
            onDismissRequest = viewModel::dismissPending,
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            DialogSurface {
                Text(
                    stringResource(
                        when (p.kind) {
                            StaffActionKind.DEACTIVATE -> R.string.staff_confirm_deactivate
                            StaffActionKind.ACTIVATE -> R.string.staff_confirm_activate
                            StaffActionKind.RESET -> R.string.staff_confirm_reset
                        },
                        p.username,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = viewModel::dismissPending, enabled = !submitting,
                        modifier = Modifier.weight(1f).then(MinButton),
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = viewModel::confirm, enabled = online && !submitting,
                        modifier = Modifier.weight(1f).then(MinButton),
                    ) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }

    if (addOpen) {
        AddStaffDialog(viewModel, error, submitting, online)
    }

    temp?.let { t ->
        // Only the [확인] button closes it; the password is cleared from the ViewModel right then.
        IdleAwareDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            DialogSurface {
                Text(
                    stringResource(R.string.temp_password_title, t.username),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.temp_password_notice), style = MaterialTheme.typography.bodyLarge)
                Text(
                    t.password,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MilkbankColors.Primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::dismissTempPassword,
                    modifier = Modifier.fillMaxWidth().then(MinButton),
                ) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}

@Composable
private fun StaffRow(s: StaffDto, actionsEnabled: Boolean, viewModel: StaffViewModel) {
    MilkCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!s.active) Text(stringResource(R.string.staff_inactive), color = MilkbankColors.Error)
            }
            Text(
                stringResource(R.string.staff_info, s.name, roleLabel(s.role)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.requestSetActive(s.id, !s.active) }, enabled = actionsEnabled,
                    modifier = Modifier.weight(1f).then(MinButton),
                ) { Text(stringResource(if (s.active) R.string.staff_deactivate else R.string.staff_activate)) }
                OutlinedButton(
                    onClick = { viewModel.requestResetPassword(s.id) }, enabled = actionsEnabled,
                    modifier = Modifier.weight(1f).then(MinButton),
                ) { Text(stringResource(R.string.staff_reset_password)) }
            }
        }
    }
}

@Composable
private fun AddStaffDialog(viewModel: StaffViewModel, error: String?, submitting: Boolean, online: Boolean) {
    var username by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("nurse") }
    IdleAwareDialog(
        onDismissRequest = viewModel::closeAdd,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        DialogSurface {
            Text(stringResource(R.string.staff_add), style = MaterialTheme.typography.titleLarge)
            IdleTextField(
                username, { username = it }, stringResource(R.string.username_label),
                Modifier.fillMaxWidth(), enabled = !submitting,
            )
            IdleTextField(
                name, { name = it }, stringResource(R.string.staff_name_label),
                Modifier.fillMaxWidth(), enabled = !submitting,
            )
            Text(stringResource(R.string.role_label), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (r in listOf("nurse", "admin")) {
                    Row(
                        Modifier.weight(1f).then(MinButton)
                            .selectable(selected = role == r, enabled = !submitting, role = Role.RadioButton) { role = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = role == r, onClick = null, enabled = !submitting)
                        Text(roleLabel(r), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::closeAdd, enabled = !submitting,
                    modifier = Modifier.weight(1f).then(MinButton),
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { viewModel.create(username.trim(), name.trim(), role) },
                    enabled = online && !submitting,
                    modifier = Modifier.weight(1f).then(MinButton),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}
