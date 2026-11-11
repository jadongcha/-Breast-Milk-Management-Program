package app.milkbank.ui.patients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import app.milkbank.R
import app.milkbank.api.PendingDto
import app.milkbank.core.formatPendingTime
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.components.IdleTextField
import java.time.Instant

@Composable
private fun PendingTitle(item: PendingDto, now: Instant) {
    Text(
        item.babyId,
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.pending_dialog_title, item.babyId, item.stationName, formatPendingTime(item.submittedAt, now)),
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
}

/** The nurse enters the count actually received; it starts at the guardian's submitted count. */
@Composable
fun ConfirmCountDialog(
    state: PendingDialog.Confirm,
    now: Instant,
    onDec: () -> Unit,
    onInc: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogShell(onDismiss, dismissable = !state.submitting) {
        PendingTitle(state.item, now)
        Text(stringResource(R.string.pending_actual_count), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            val decrease = stringResource(R.string.decrease_pack)
            OutlinedButton(
                onClick = onDec, enabled = !state.submitting && state.count > 1,
                modifier = MinButton.semantics { contentDescription = decrease },
            ) { Text("−", Modifier.clearAndSetSemantics { }) }
            Text(
                stringResource(R.string.packs, state.count),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            val increase = stringResource(R.string.increase_pack)
            OutlinedButton(
                onClick = onInc, enabled = !state.submitting && state.count < 100,
                modifier = MinButton.semantics { contentDescription = increase },
            ) { Text("+", Modifier.clearAndSetSemantics { }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, enabled = !state.submitting, modifier = Modifier.weight(1f).then(MinButton)) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onConfirm,
                enabled = !state.submitting && LocalOnline.current,
                modifier = Modifier.weight(1f).then(MinButton),
            ) { Text(stringResource(R.string.pending_confirm)) }
        }
    }
}

@Composable
fun RejectDialog(
    state: PendingDialog.Reject,
    now: Instant,
    canReject: Boolean,
    onReason: (String) -> Unit,
    onMemo: (String) -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogShell(onDismiss, dismissable = !state.submitting) {
        PendingTitle(state.item, now)
        Text(stringResource(R.string.pending_reject_title), style = MaterialTheme.typography.titleMedium)
        for ((value, label) in listOf(
            REJECT_NOT_RECEIVED to R.string.reject_not_received,
            REJECT_OTHER to R.string.reject_other,
        )) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                    selected = state.reason == value,
                    enabled = !state.submitting,
                    role = Role.RadioButton,
                    onClick = { onReason(value) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.reason == value, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (state.reason == REJECT_OTHER) {
            IdleTextField(
                state.memo, onMemo, stringResource(R.string.reject_memo_label), Modifier.fillMaxWidth(),
                singleLine = false,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, enabled = !state.submitting, modifier = Modifier.weight(1f).then(MinButton)) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onReject,
                enabled = canReject && LocalOnline.current,
                modifier = Modifier.weight(1f).then(MinButton),
            ) { Text(stringResource(R.string.pending_reject_button)) }
        }
    }
}

/** Shown after a confirmed intake: what was received and the new stock, so the nurse can double-check. */
@Composable
fun PendingDoneDialog(state: PendingDialog.Done, now: Instant, onClose: () -> Unit) {
    DialogShell(onClose) {
        PendingTitle(state.item, now)
        Text(
            stringResource(R.string.pending_done_received, state.received),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.pending_done_stock, state.balance),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.pending_done_formula, state.before, state.received),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().then(MinButton)) {
            Text(stringResource(R.string.pending_confirm))
        }
    }
}
