package app.milkbank.ui.patients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.components.packsWithMl
import app.milkbank.api.RecordDto
import app.milkbank.core.formatKst
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.MilkbankColors

/** The baby ID is shown, never the baby name. [onEdit] is non-null only for admins. */
@Composable
fun PatientDetailScreen(
    viewModel: PatientDetailViewModel,
    onEdit: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val pendingCancel by viewModel.pendingCancel.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Box(modifier.fillMaxSize()) {
        val d = detail
        if (d == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.detail_title, d.regNo),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.bed_info, d.bed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.guardian_info, d.guardianName, d.guardianPhone),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onEdit != null) {
                        OutlinedButton(onClick = { onEdit(d.id) }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.edit_patient))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }

                MilkCard(container = MilkbankColors.PrimaryLight) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.confirmed_stock), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.packs, d.balance),
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (d.balance == 0) MilkbankColors.Error else MilkbankColors.Primary,
                        )
                    }
                }

                val canSave = online && !submitting
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { dialog = "intake" }, enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    ) { Text(stringResource(R.string.kind_intake), style = MaterialTheme.typography.titleLarge) }
                    SecondaryButton(
                        stringResource(R.string.kind_use), canSave, Modifier.weight(1f),
                    ) { dialog = "use" }
                    SecondaryButton(
                        stringResource(R.string.kind_discard), canSave, Modifier.weight(1f),
                    ) { dialog = "discard" }
                }

                Text(stringResource(R.string.records_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (d.records.isEmpty()) {
                    Text(stringResource(R.string.records_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MilkCard {
                        d.records.forEach { r ->
                            RecordRow(r, canCancel = viewModel.canCancel(r), enabled = canSave) { viewModel.askCancel(r) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (dialog) {
        "intake" -> IntakeDialog(onDismiss = { dialog = null }) { c, m, ml ->
            dialog = null
            viewModel.request("intake", c, null, m, ml)
        }
        "use" -> UseDialog(onDismiss = { dialog = null }) { c, m, ml ->
            dialog = null
            viewModel.request("use", c, null, m, ml)
        }
        "discard" -> DiscardDialog(onDismiss = { dialog = null }) { c, r, m, ml ->
            dialog = null
            viewModel.request("discard", c, r, m, ml)
        }
    }
    val regNo = detail?.regNo
    val p = pending
    if (p != null && regNo != null) {
        ConfirmStockDialog(regNo, p, submitting, onConfirm = viewModel::confirm, onDismiss = viewModel::dismissPending)
    }
    val cancelRecord = pendingCancel
    if (cancelRecord != null) {
        ConfirmCancelDialog(cancelRecord, submitting, onConfirm = { viewModel.cancelRecord(cancelRecord.id) }, onDismiss = viewModel::dismissCancel)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(text, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun RecordRow(r: RecordDto, canCancel: Boolean, enabled: Boolean, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (r.canceled) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatKst(r.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val head = buildString {
                append(kindLabel(r.kind)); append(' '); append(packsWithMl(r.count, r.ml))
            }
            Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            r.discardReason?.let { Text(reasonLabel(it)) }
            Text(r.staffName, style = MaterialTheme.typography.bodyMedium)
            r.memo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (r.canceled) Text(stringResource(R.string.record_canceled), color = MaterialTheme.colorScheme.error)
        }
        if (canCancel) {
            OutlinedButton(onClick = onCancel, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
