package app.milkbank.ui.patients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.milkbank.R
import app.milkbank.api.RecordDto
import app.milkbank.core.formatKst
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.components.IdleTextField
import app.milkbank.api.PackDto
import app.milkbank.core.formatKstDay
import app.milkbank.ui.components.PackMlFields
import app.milkbank.ui.components.recordMlText
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox

internal val MinButton = Modifier.heightIn(min = 56.dp)

@Composable
internal fun DialogShell(
    onDismiss: () -> Unit,
    dismissable: Boolean = true,
    content: @Composable () -> Unit,
) {
    IdleAwareDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = dismissable, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

/** Buttons 1..10 in two rows. */
@Composable
private fun CountPicker(selected: Int?, onSelect: (Int) -> Unit) {
    Text(stringResource(R.string.pack_count_label), style = MaterialTheme.typography.titleMedium)
    for (row in listOf(1..5, 6..10)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in row) {
                val m = Modifier.weight(1f).then(MinButton)
                if (n == selected) Button(onClick = { onSelect(n) }, modifier = m, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("$n") }
                else OutlinedButton(onClick = { onSelect(n) }, modifier = m, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("$n") }
            }
        }
    }
}

@Composable
private fun DialogButtons(
    primaryText: String,
    primaryEnabled: Boolean,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).then(MinButton)) {
            Text(stringResource(R.string.cancel))
        }
        Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f).then(MinButton)) {
            Text(primaryText)
        }
    }
}

/** The intake, use or discard window; while "팩 고르기" is open it shows the pack list instead. */
@Composable
fun StockDialog(state: StockDialogState, packs: List<PackDto>, vm: PatientDetailViewModel) {
    if (state.choosing) ChoosePacksDialog(state, packs, vm)
    else when (state.kind) {
        "intake" -> IntakeDialog(state, vm)
        "use" -> UseDialog(state, vm)
        else -> DiscardDialog(state, vm)
    }
}

@Composable
private fun IntakeDialog(state: StockDialogState, vm: PatientDetailViewModel) {
    DialogShell(vm::closeDialog) {
        Text(stringResource(R.string.kind_intake), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        CountPicker(state.count, vm::setCount)
        if (state.count != null) PackMlFields(state.packMls, vm::setPackMls, 56.dp, Modifier.fillMaxWidth())
        IdleTextField(state.memo, vm::setMemo, stringResource(R.string.memo_label), Modifier.fillMaxWidth(), singleLine = false)
        DialogButtons(stringResource(R.string.kind_intake), state.canSubmit, vm::closeDialog, vm::submitDialog)
    }
}

@Composable
private fun UseDialog(state: StockDialogState, vm: PatientDetailViewModel) {
    val count = state.count ?: 1
    DialogShell(vm::closeDialog) {
        Text(stringResource(R.string.kind_use), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            val decrease = stringResource(R.string.decrease_pack)
            OutlinedButton(
                onClick = { if (count > 1) vm.setCount(count - 1) }, enabled = count > 1,
                modifier = MinButton.semantics { contentDescription = decrease },
            ) { Text("−", Modifier.clearAndSetSemantics { }) }
            Text(stringResource(R.string.packs, count), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val increase = stringResource(R.string.increase_pack)
            OutlinedButton(
                onClick = { if (count < MAX_PACKS) vm.setCount(count + 1) }, enabled = count < MAX_PACKS,
                modifier = MinButton.semantics { contentDescription = increase },
            ) { Text("+", Modifier.clearAndSetSemantics { }) }
        }
        PacksToGo(state, vm)
        IdleTextField(state.memo, vm::setMemo, stringResource(R.string.memo_label), Modifier.fillMaxWidth(), singleLine = false)
        DialogButtons(stringResource(R.string.kind_use), state.canSubmit, vm::closeDialog, vm::submitDialog)
    }
}

@Composable
private fun DiscardDialog(state: StockDialogState, vm: PatientDetailViewModel) {
    DialogShell(vm::closeDialog) {
        Text(stringResource(R.string.kind_discard), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        CountPicker(state.count, vm::setCount)
        if (state.count != null) PacksToGo(state, vm)
        Text(stringResource(R.string.discard_reason_label), style = MaterialTheme.typography.titleMedium)
        for (r in DISCARD_REASONS) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = state.reason == r, role = Role.RadioButton, onClick = { vm.setReason(r) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.reason == r, onClick = null)
                Text(reasonLabel(r), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        IdleTextField(state.memo, vm::setMemo, stringResource(R.string.memo_label), Modifier.fillMaxWidth(), singleLine = false)
        DialogButtons(stringResource(R.string.kind_discard), state.canSubmit, vm::closeDialog, vm::submitDialog)
    }
}

/** "나갈 팩: 80mL, 80mL" with the button to pick other packs. */
@Composable
private fun PacksToGo(state: StockDialogState, vm: PatientDetailViewModel) {
    if (state.previewPacks.isNotEmpty()) {
        Text(stringResource(R.string.packs_to_go, packMlsLabel(state.previewPacks)), style = MaterialTheme.typography.titleMedium)
    }
    OutlinedButton(onClick = vm::openChoosePacks, modifier = Modifier.fillMaxWidth().then(MinButton)) {
        Text(stringResource(R.string.choose_packs))
    }
}

/** The remaining packs, oldest first; tick exactly as many as the pack count. */
@Composable
private fun ChoosePacksDialog(state: StockDialogState, packs: List<PackDto>, vm: PatientDetailViewModel) {
    val picking = state.picking.orEmpty()
    DialogShell(vm::closeDialog) {
        Text(stringResource(R.string.choose_packs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.packs, state.count ?: 0), style = MaterialTheme.typography.titleMedium)
        packs.forEach { pk ->
            val checked = pk.id in picking
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { vm.togglePack(pk.id) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(
                    stringResource(R.string.remaining_pack_line, formatKstDay(pk.createdAt), packMlLabel(pk.ml)),
                    Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        DialogButtons(stringResource(R.string.choose_packs_done), picking.size == state.count, vm::closeDialog, vm::finishChoosePacks)
    }
}

/** The safety check: the baby ID is shown large. [submitting] blocks both buttons. */
@Composable
fun ConfirmStockDialog(
    regNo: String,
    action: PendingAction,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = kindLabel(action.kind)
    DialogShell(onDismiss, dismissable = !submitting) {
        Text(
            regNo,
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.confirm_stock, regNo, action.count, label),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        action.packMls?.let { mls ->
            val known = mls.filterNotNull()
            Text(
                recordMlText(action.count, if (known.isEmpty()) null else known.sum(), mls.size - known.size),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
        }
        if (action.goingPacks.isNotEmpty()) {
            Text(
                stringResource(R.string.packs_to_go, packMlsLabel(action.goingPacks)),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
        }
        action.reason?.let { Text(reasonLabel(it), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.weight(1f).then(MinButton)) {
                Text(stringResource(R.string.cancel))
            }
            Button(onClick = onConfirm, enabled = !submitting && LocalOnline.current, modifier = Modifier.weight(1f).then(MinButton)) {
                Text(stringResource(R.string.confirm))
            }
        }
    }
}

@Composable
fun ConfirmCancelDialog(record: RecordDto, submitting: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogShell(onDismiss, dismissable = !submitting) {
        Text(stringResource(R.string.cancel_confirm), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        // Which record: kind, packs, time and staff, so a wrong row is noticed before confirming.
        Text(
            stringResource(R.string.cancel_record_summary, kindLabel(record.kind), record.count, formatKst(record.createdAt), record.staffName),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.weight(1f).then(MinButton)) {
                Text(stringResource(R.string.back))
            }
            Button(onClick = onConfirm, enabled = !submitting && LocalOnline.current, modifier = Modifier.weight(1f).then(MinButton)) {
                Text(stringResource(R.string.confirm))
            }
        }
    }
}
