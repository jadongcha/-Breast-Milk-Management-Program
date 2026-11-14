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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.milkbank.ui.components.MlField
import app.milkbank.ui.components.MlParse
import app.milkbank.ui.components.parseMl

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

@Composable
fun IntakeDialog(onDismiss: () -> Unit, onNext: (count: Int, memo: String, ml: String) -> Unit) {
    var count by rememberSaveable { mutableStateOf<Int?>(null) }
    var ml by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    DialogShell(onDismiss) {
        Text(stringResource(R.string.kind_intake), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        CountPicker(count) { count = it }
        MlField(ml, { ml = it }, stringResource(R.string.ml_label), Modifier.fillMaxWidth())
        IdleTextField(memo, { memo = it }, stringResource(R.string.memo_label), Modifier.fillMaxWidth(), singleLine = false)
        DialogButtons(stringResource(R.string.kind_intake), count != null && parseMl(ml) != MlParse.Invalid, onDismiss) { count?.let { onNext(it, memo, ml) } }
    }
}

@Composable
fun UseDialog(onDismiss: () -> Unit, onNext: (count: Int, memo: String, ml: String) -> Unit) {
    var count by rememberSaveable { mutableIntStateOf(1) }
    var ml by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    DialogShell(onDismiss) {
        Text(stringResource(R.string.kind_use), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            val decrease = stringResource(R.string.decrease_pack)
            OutlinedButton(
                onClick = { if (count > 1) count-- }, enabled = count > 1,
                modifier = MinButton.semantics { contentDescription = decrease },
            ) { Text("−", Modifier.clearAndSetSemantics { }) }
            Text(stringResource(R.string.packs, count), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val increase = stringResource(R.string.increase_pack)
            OutlinedButton(
                onClick = { if (count < MAX_PACKS) count++ }, enabled = count < MAX_PACKS,
                modifier = MinButton.semantics { contentDescription = increase },
            ) { Text("+", Modifier.clearAndSetSemantics { }) }
        }
        MlField(ml, { ml = it }, stringResource(R.string.ml_label), Modifier.fillMaxWidth())
        IdleTextField(memo, { memo = it }, stringResource(R.string.memo_label), Modifier.fillMaxWidth(), singleLine = false)
        DialogButtons(stringResource(R.string.kind_use), parseMl(ml) != MlParse.Invalid, onDismiss) { onNext(count, memo, ml) }
    }
}

@Composable
fun DiscardDialog(onDismiss: () -> Unit, onNext: (count: Int, reason: String, memo: String, ml: String) -> Unit) {
    var count by rememberSaveable { mutableStateOf<Int?>(null) }
    var ml by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var memo by rememberSaveable { mutableStateOf("") }
    DialogShell(onDismiss) {
        Text(stringResource(R.string.kind_discard), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        CountPicker(count) { count = it }
        MlField(ml, { ml = it }, stringResource(R.string.ml_label), Modifier.fillMaxWidth())
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
        IdleTextField(memo, { memo = it }, stringResource(R.string.memo_label), Modifier.fillMaxWidth(), singleLine = false)
        DialogButtons(stringResource(R.string.kind_discard), count != null && reason != null && parseMl(ml) != MlParse.Invalid, onDismiss) {
            val c = count
            val r = reason
            if (c != null && r != null) onNext(c, r, memo, ml)
        }
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
        action.ml?.let { Text(stringResource(R.string.ml_value, it), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
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
