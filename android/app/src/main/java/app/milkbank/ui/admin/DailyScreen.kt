package app.milkbank.ui.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.core.todayKst
import app.milkbank.core.SystemClock
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.patients.MilkCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

private val MinButton = Modifier.heightIn(min = 56.dp)
private val IdW: Dp = 88.dp
private val BedW: Dp = 72.dp
private val CellW: Dp = 112.dp
private val ReturnW: Dp = 88.dp

@Composable
private fun packsMl(packs: Int, ml: Int?): String =
    if (ml != null) stringResource(R.string.packs_ml, packs, ml) else stringResource(R.string.packs, packs)

/** Daily usage: a date's table, a 7/30-day usage chart, and CSV export to a file the user picks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyScreen(viewModel: DailyViewModel, onBack: () -> Unit) {
    val date by viewModel.date.collectAsStateWithLifecycle()
    val daily by viewModel.daily.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val patientId by viewModel.patientId.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val selectedBar by viewModel.selectedBar.collectAsStateWithLifecycle()
    val babies by viewModel.babies.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val dailyError by viewModel.dailyError.collectAsStateWithLifecycle()
    val seriesError by viewModel.seriesError.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    // The range chosen in the dialog is kept while the system file dialog is open.
    var pendingFrom by rememberSaveable { mutableStateOf("") }
    var pendingTo by rememberSaveable { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val from = runCatching { LocalDate.parse(pendingFrom) }.getOrNull()
        val to = runCatching { LocalDate.parse(pendingTo) }.getOrNull()
        if (from != null && to != null) {
            viewModel.onDocumentChosen(uri, from, to) { target, bytes ->
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(target) ?: throw IOException("no output stream")
                    out.use { it.write(bytes) }
                }
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.menu_daily)) },
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
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge) }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.daily_date_label), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { pickingDate = true }, modifier = MinButton) { Text(date.toString()) }
            }

            dailyError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            DailyTable(daily)

            Text(stringResource(R.string.chart_title), style = MaterialTheme.typography.titleMedium)
            ChartControls(days, patientId, babies, viewModel::setDays, viewModel::setPatient)
            seriesError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            if (series.isNotEmpty()) {
                BarChart(
                    values = series.map { it.usePacks },
                    labels = series.map { it.day.takeLast(5) },
                    descriptions = series.map { stringResource(R.string.chart_bar_desc, it.day.takeLast(5), it.usePacks) },
                    selected = selectedBar,
                    onSelect = viewModel::selectBar,
                )
                selectedBar?.let { i ->
                    series.getOrNull(i)?.let { p ->
                        Text(
                            if (p.useMl != null) {
                                stringResource(R.string.chart_bar_detail_ml, p.day, p.inPacks, p.usePacks, p.discardPacks, p.useMl)
                            } else {
                                stringResource(R.string.chart_bar_detail, p.day, p.inPacks, p.usePacks, p.discardPacks)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val today = todayKst(SystemClock.now())
                    pendingFrom = today.minusDays(6).toString()
                    pendingTo = today.toString()
                    exportOpen = true
                },
                modifier = Modifier.fillMaxWidth().then(MinButton),
            ) { Text(stringResource(R.string.csv_export)) }
        }
    }

    if (pickingDate) {
        AdmittedOnPicker(
            current = date.toString(),
            onPick = { picked ->
                pickingDate = false
                runCatching { LocalDate.parse(picked) }.getOrNull()?.let(viewModel::setDate)
            },
            onDismiss = { pickingDate = false },
        )
    }

    if (exportOpen) {
        ExportDialog(
            from = pendingFrom, to = pendingTo,
            rangeError = { f, t -> viewModel.exportRange(f, t) },
            onFrom = { pendingFrom = it }, onTo = { pendingTo = it },
            onDismiss = { exportOpen = false },
            onConfirm = {
                exportOpen = false
                launcher.launch("milkbank-$pendingFrom-$pendingTo.csv")
            },
        )
    }
}

@Composable
private fun ChartControls(
    days: Int, patientId: Int?, babies: List<ChartBaby>, onDays: (Int) -> Unit, onPatient: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = days == 7, onClick = { onDays(7) },
                label = { Text(stringResource(R.string.chart_7)) }, modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = days == 30, onClick = { onDays(30) },
                label = { Text(stringResource(R.string.chart_30)) }, modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = patientId == null, onClick = { onPatient(null) },
                label = { Text(stringResource(R.string.chart_all)) }, modifier = Modifier.heightIn(min = 48.dp),
            )
            for (b in babies) {
                FilterChip(
                    selected = patientId == b.patientId, onClick = { onPatient(b.patientId) },
                    label = { Text(b.babyId) }, modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun DailyTable(daily: app.milkbank.api.DailyDto?) {
    if (daily == null) return
    if (daily.rows.isEmpty()) {
        Text(stringResource(R.string.daily_empty), style = MaterialTheme.typography.bodyLarge)
        return
    }
    MilkCard {
        Column(
            Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.daily_ml_header), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val head = MaterialTheme.typography.labelLarge
            Row {
                Text(stringResource(R.string.col_baby_id), Modifier.width(IdW), style = head)
                Text(stringResource(R.string.col_bed), Modifier.width(BedW), style = head)
                Text(stringResource(R.string.kind_intake), Modifier.width(CellW), style = head)
                Text(stringResource(R.string.kind_use), Modifier.width(CellW), style = head)
                Text(stringResource(R.string.kind_discard), Modifier.width(CellW), style = head)
                Text(stringResource(R.string.daily_col_return), Modifier.width(ReturnW), style = head)
            }
            val st = MaterialTheme.typography.bodyLarge
            for (r in daily.rows) {
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.babyId, Modifier.width(IdW), style = st, fontWeight = FontWeight.Bold)
                    Text(r.bed, Modifier.width(BedW), style = st)
                    Text(packsMl(r.inPacks, r.inMl), Modifier.width(CellW), style = st)
                    Text(packsMl(r.usePacks, r.useMl), Modifier.width(CellW), style = st)
                    Text(packsMl(r.discardPacks, r.discardMl), Modifier.width(CellW), style = st)
                    Text(stringResource(R.string.packs, r.returnPacks), Modifier.width(ReturnW), style = st)
                }
            }
            val t = daily.total
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.daily_total), Modifier.width(IdW + BedW), style = st, fontWeight = FontWeight.Bold)
                Text(packsMl(t.inPacks, t.inMl), Modifier.width(CellW), style = st, fontWeight = FontWeight.Bold)
                Text(packsMl(t.usePacks, t.useMl), Modifier.width(CellW), style = st, fontWeight = FontWeight.Bold)
                Text(packsMl(t.discardPacks, t.discardMl), Modifier.width(CellW), style = st, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.packs, t.returnPacks), Modifier.width(ReturnW), style = st, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExportDialog(
    from: String, to: String,
    rangeError: (LocalDate, LocalDate) -> String?,
    onFrom: (String) -> Unit, onTo: (String) -> Unit,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val fromDate = runCatching { LocalDate.parse(from) }.getOrNull()
    val toDate = runCatching { LocalDate.parse(to) }.getOrNull()
    val problem = if (fromDate != null && toDate != null) rangeError(fromDate, toDate) else null

    IdleAwareDialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.csv_export), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.csv_from), Modifier.width(64.dp))
                    OutlinedButton(onClick = { picking = "from" }, modifier = MinButton) { Text(from) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.csv_to), Modifier.width(64.dp))
                    OutlinedButton(onClick = { picking = "to" }, modifier = MinButton) { Text(to) }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = MinButton) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = onConfirm,
                        enabled = fromDate != null && toDate != null && problem == null,
                        modifier = MinButton,
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }

    when (picking) {
        "from" -> AdmittedOnPicker(from, { onFrom(it); picking = null }, { picking = null })
        "to" -> AdmittedOnPicker(to, { onTo(it); picking = null }, { picking = null })
    }
}
