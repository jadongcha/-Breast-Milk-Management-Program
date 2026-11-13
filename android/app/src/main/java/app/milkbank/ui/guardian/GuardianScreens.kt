package app.milkbank.ui.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.api.BabyDto
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.components.IdleTextField

private val BUTTON_MIN_HEIGHT = 64.dp

@Composable
private fun GuardianButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
) {
    val m = modifier.heightIn(min = BUTTON_MIN_HEIGHT)
    if (outlined) {
        OutlinedButton(onClick, m, enabled = enabled) { Text(text, style = MaterialTheme.typography.titleLarge) }
    } else {
        Button(onClick, m, enabled = enabled) { Text(text, style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * Header plus the guardian steps (or, when [invalid], the station-invalid message in their place), shared by the
 * station tablet and general hospital devices. All state comes from [vm]; nothing is kept here.
 */
@Composable
fun GuardianFlow(vm: GuardianViewModel, header: String, invalid: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(
            header,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp), // clear of the hidden corner area
        )
        if (invalid) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.station_invalid_message),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            GuardianSteps(vm)
        }
    }
}

@Composable
private fun GuardianSteps(vm: GuardianViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val s = step) {
                is GuardianStep.Start -> StartStep(s, vm, online)
                is GuardianStep.SelectBaby -> {
                    Title(stringResource(R.string.guardian_select_baby))
                    s.babies.forEach { b ->
                        GuardianButton(b.babyId, { vm.selectBaby(b) }, Modifier.fillMaxWidth())
                    }
                    GuardianButton(stringResource(R.string.guardian_finish), vm::finish, Modifier.fillMaxWidth(), outlined = true)
                }
                is GuardianStep.Stock -> StockStep(s, vm, online)
                is GuardianStep.PickCount -> PickStep(s.baby, vm)
                is GuardianStep.ConfirmSubmit -> {
                    PickStep(s.baby, vm)
                    ConfirmDialog(s, vm, submitting, online)
                }
                is GuardianStep.Done -> DoneStep(s, vm)
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
}

@Composable
private fun StartStep(s: GuardianStep.Start, vm: GuardianViewModel, online: Boolean) {
    val canLogin = online && !s.submitting && s.phone.isNotBlank() && s.babyId.isNotBlank()
    IdleTextField(
        value = s.phone,
        onValueChange = vm::onPhone,
        label = stringResource(R.string.guardian_phone_label),
        modifier = Modifier.fillMaxWidth(),
        enabled = !s.submitting,
        // NumberPassword: digit pad that IMEs treat as incognito (no learning, no suggestions) - the next parent must not see this number.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        textStyle = MaterialTheme.typography.titleLarge,
    )
    IdleTextField(
        value = s.babyId,
        onValueChange = vm::onBabyId,
        label = stringResource(R.string.guardian_baby_id_label),
        modifier = Modifier.fillMaxWidth(),
        enabled = !s.submitting,
        // Password type (text still visible): Gboard and others do not learn or suggest from it, so the next parent never sees this ID.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        visualTransformation = VisualTransformation.None,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardActions = KeyboardActions(onDone = { if (canLogin) vm.login() }),
    )
    s.error?.let {
        Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    GuardianButton(stringResource(R.string.guardian_ok), vm::login, Modifier.fillMaxWidth(), enabled = canLogin)
}

@Composable
private fun StockStep(s: GuardianStep.Stock, vm: GuardianViewModel, online: Boolean) {
    Title(stringResource(R.string.guardian_stock_title))
    Text(s.baby.babyId, style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.guardian_confirmed_stock), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.packs, s.baby.balance),
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (s.baby.pendingCount > 0) {
        Text(stringResource(R.string.guardian_pending, s.baby.pendingCount), style = MaterialTheme.typography.titleLarge)
    }
    s.notice?.let {
        Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    Text(stringResource(R.string.guardian_view_only_hint), style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GuardianButton(
            stringResource(R.string.guardian_submit_button), vm::startSubmit, Modifier.weight(1f), enabled = online,
        )
        GuardianButton(stringResource(R.string.guardian_finish), vm::finish, Modifier.weight(1f), outlined = true)
    }
}

@Composable
private fun PickStep(baby: BabyDto, vm: GuardianViewModel) {
    Title(stringResource(R.string.guardian_count_title))
    Text(baby.babyId, style = MaterialTheme.typography.titleLarge)
    for (row in listOf(1..5, 6..10)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (n in row) {
                GuardianButton(n.toString(), { vm.pickCount(n) }, Modifier.weight(1f))
            }
        }
    }
    GuardianButton(stringResource(R.string.back), vm::back, Modifier.fillMaxWidth(), outlined = true)
}

@Composable
private fun ConfirmDialog(s: GuardianStep.ConfirmSubmit, vm: GuardianViewModel, submitting: Boolean, online: Boolean) {
    IdleAwareDialog(onDismissRequest = vm::back) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    stringResource(R.string.guardian_submit_confirm, s.baby.babyId, s.count),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuardianButton(stringResource(R.string.cancel), vm::back, Modifier.weight(1f), enabled = !submitting, outlined = true)
                    GuardianButton(stringResource(R.string.guardian_submit), vm::submit, Modifier.weight(1f), enabled = online && !submitting)
                }
            }
        }
    }
}

@Composable
private fun DoneStep(s: GuardianStep.Done, vm: GuardianViewModel) {
    Title(stringResource(R.string.guardian_done_title))
    Text(stringResource(R.string.guardian_done_submitted, s.count), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.guardian_done_stock, s.balance), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.guardian_done_note, s.count),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.heightIn(min = 8.dp))
    GuardianButton(stringResource(R.string.guardian_finish), vm::finish, Modifier.fillMaxWidth())
}
