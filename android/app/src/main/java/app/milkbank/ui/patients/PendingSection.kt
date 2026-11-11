package app.milkbank.ui.patients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.api.PendingDto
import app.milkbank.core.formatPendingTime
import app.milkbank.ui.MilkbankColors

private val TABLE_MIN_WIDTH: Dp = 560.dp

/** Body of the pending card: table (wide) or one card per row (narrow), plus the confirm/reject dialogs. */
@Composable
fun PendingSection(viewModel: PendingViewModel, modifier: Modifier = Modifier) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val refreshError by viewModel.refreshError.collectAsStateWithLifecycle()
    val now = viewModel.now()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
        refreshError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (items.isEmpty()) {
            Text(stringResource(R.string.pending_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= TABLE_MIN_WIDTH) {
                    PendingTable(items, now, viewModel::openConfirm, viewModel::openReject)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { PendingCardRow(it, now, viewModel::openConfirm, viewModel::openReject) }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is PendingDialog.Confirm -> ConfirmCountDialog(
            d, now, viewModel::dec, viewModel::inc, viewModel::confirm, viewModel::close,
        )
        is PendingDialog.Reject -> RejectDialog(
            d, now, viewModel.canReject(d), viewModel::setReason, viewModel::setMemo, viewModel::reject, viewModel::close,
        )
        is PendingDialog.Done -> PendingDoneDialog(d, now, viewModel::close)
        null -> Unit
    }
}

@Composable
private fun RowButtons(item: PendingDto, onConfirm: (PendingDto) -> Unit, onReject: (PendingDto) -> Unit) {
    // A discharged baby cannot receive stock: only rejecting is offered.
    if (!item.discharged) {
        Button(onClick = { onConfirm(item) }, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.pending_confirm_button))
        }
    }
    OutlinedButton(onClick = { onReject(item) }, modifier = Modifier.heightIn(min = 56.dp)) {
        Text(stringResource(R.string.pending_reject_button))
    }
}

@Composable
private fun PendingTable(
    items: List<PendingDto>,
    now: java.time.Instant,
    onConfirm: (PendingDto) -> Unit,
    onReject: (PendingDto) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell(R.string.pending_col_baby, 1.3f)
            HeaderCell(R.string.pending_col_stock, 0.9f)
            HeaderCell(R.string.pending_col_station, 1.3f)
            HeaderCell(R.string.pending_col_time, 1.5f)
            HeaderCell(R.string.pending_col_count, 0.8f)
            Row(Modifier.weight(2.4f)) {}
        }
        items.forEach { p ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.babyId, Modifier.weight(1.3f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.packs, p.balance), Modifier.weight(0.9f), style = MaterialTheme.typography.bodyLarge)
                Text(p.stationName, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyLarge)
                Text(formatPendingTime(p.submittedAt, now), Modifier.weight(1.5f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.packs, p.count), Modifier.weight(0.8f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Row(Modifier.weight(2.4f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    RowButtons(p, onConfirm, onReject)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(res: Int, weight: Float) {
    Text(
        stringResource(res), Modifier.weight(weight),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PendingCardRow(
    p: PendingDto,
    now: java.time.Instant,
    onConfirm: (PendingDto) -> Unit,
    onReject: (PendingDto) -> Unit,
) {
    MilkCard(container = MilkbankColors.Background) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.babyId, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.pending_label_value, stringResource(R.string.pending_col_stock), stringResource(R.string.packs, p.balance)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                stringResource(R.string.pending_station_time, p.stationName, formatPendingTime(p.submittedAt, now)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pending_label_value, stringResource(R.string.pending_col_count), stringResource(R.string.packs, p.count)),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RowButtons(p, onConfirm, onReject) }
            }
        }
    }
}
