package app.milkbank.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.api.OverviewDto
import app.milkbank.api.OverviewRowDto
import app.milkbank.ui.MilkbankColors
import app.milkbank.ui.patients.MilkCard

private val MinButton = Modifier.heightIn(min = 56.dp)
private val WideBreakpoint = 600.dp
private val LowColor = Color(0xFFD9822B)
private val BedW: Dp = 72.dp
private val StatusW: Dp = 64.dp

@Composable
private fun statusMark(status: String) = stringResource(
    when (status) {
        "empty" -> R.string.status_mark_empty
        "low" -> R.string.status_mark_low
        else -> R.string.status_mark_ok
    },
)

/** What TalkBack says for the coloured mark. */
@Composable
private fun statusDesc(status: String) = stringResource(
    when (status) {
        "empty" -> R.string.status_desc_empty
        "low" -> R.string.status_desc_low
        else -> R.string.status_desc_ok
    },
)

private fun statusColor(status: String) = when (status) {
    "empty" -> MilkbankColors.Error
    "low" -> LowColor
    else -> MilkbankColors.Primary
}

private fun Double?.oneDecimal(none: String) = this?.let { "%.1f".format(it) } ?: none

@Composable
private fun packsMl(packs: Int, ml: Int?): String =
    if (ml != null) stringResource(R.string.packs_ml, packs, ml) else stringResource(R.string.packs, packs)

private fun OverviewSort.labelRes() = when (this) {
    OverviewSort.STATUS -> R.string.sort_status
    OverviewSort.BED -> R.string.sort_bed
    OverviewSort.BALANCE -> R.string.sort_balance
}

/** Admin overview: today's summary, then one row per baby. Tapping a row opens that baby. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(viewModel: OverviewViewModel, onBack: () -> Unit, onOpenPatient: (Int) -> Unit) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val lowOnly by viewModel.lowOnly.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.menu_overview)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp).then(MinButton)) {
                        Text(stringResource(R.string.back_with_arrow))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::load, enabled = !loading, modifier = MinButton) {
                        Text(stringResource(R.string.overview_refresh))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding)) {
            val wide = maxWidth >= WideBreakpoint
            LazyColumn(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let {
                    item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                }
                overview?.let { item { Summary(it) } }
                item { Controls(sort, lowOnly, viewModel::setSort, viewModel::setLowOnly) }
                if (wide) item { TableHeader() }
                items(rows, key = { it.patientId }) { r ->
                    if (wide) TableRow(r) { onOpenPatient(r.patientId) } else CardRow(r) { onOpenPatient(r.patientId) }
                }
            }
        }
    }
}

@Composable
private fun Summary(o: OverviewDto) {
    MilkCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    stringResource(R.string.overview_admitted, o.admitted),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.overview_low, o.low), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (o.low > 0) MilkbankColors.Error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    stringResource(R.string.overview_today_in_value, packsMl(o.todayInPacks, o.todayInMl)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.overview_today_use_value, packsMl(o.todayUsePacks, o.todayUseMl)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                stringResource(R.string.overview_pending, o.pendingSubmissions),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun Controls(
    sort: OverviewSort, lowOnly: Boolean, onSort: (OverviewSort) -> Unit, onLowOnly: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (s in OverviewSort.entries) {
                FilterChip(
                    selected = sort == s, onClick = { onSort(s) },
                    label = { Text(stringResource(s.labelRes())) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onLowOnly(!lowOnly) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = lowOnly, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.overview_low_only), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val st = MaterialTheme.typography.labelLarge
        Text(stringResource(R.string.col_bed), Modifier.width(BedW), style = st)
        Text(stringResource(R.string.col_baby_id), Modifier.weight(1.4f), style = st)
        Text(stringResource(R.string.col_balance), Modifier.weight(1f), style = st)
        Text(stringResource(R.string.col_daily_avg), Modifier.weight(1f), style = st)
        Text(stringResource(R.string.col_days_left), Modifier.weight(1f), style = st)
        Text(stringResource(R.string.col_status), Modifier.width(StatusW), style = st)
        Text(stringResource(R.string.col_pending), Modifier.weight(1f), style = st)
    }
}

@Composable
private fun TableRow(r: OverviewRowDto, onClick: () -> Unit) {
    val none = stringResource(R.string.value_none)
    MilkCard(Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val st = MaterialTheme.typography.titleMedium
            Text(r.bed, Modifier.width(BedW), style = st)
            Text(r.babyId, Modifier.weight(1.4f), style = st, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.packs, r.balance), Modifier.weight(1f), style = st,
                color = if (r.status == "empty") MilkbankColors.Error else MaterialTheme.colorScheme.onSurface,
            )
            Text(r.dailyAvg.oneDecimal(none), Modifier.weight(1f), style = st)
            Text(r.daysLeft.oneDecimal(none), Modifier.weight(1f), style = st)
            val markDesc = statusDesc(r.status)
            Text(
                statusMark(r.status), Modifier.width(StatusW).semantics { contentDescription = markDesc }, style = st,
            )
            Text(
                if (r.pendingPacks > 0) stringResource(R.string.packs, r.pendingPacks) else none,
                Modifier.weight(1f), style = st,
            )
        }
    }
}

@Composable
private fun CardRow(r: OverviewRowDto, onClick: () -> Unit) {
    val none = stringResource(R.string.value_none)
    val detail = if (r.pendingPacks > 0) {
        stringResource(R.string.overview_row_detail_pending, r.dailyAvg.oneDecimal(none), r.daysLeft.oneDecimal(none), r.pendingPacks)
    } else {
        stringResource(R.string.overview_row_detail, r.dailyAvg.oneDecimal(none), r.daysLeft.oneDecimal(none))
    }
    val markDesc = statusDesc(r.status)
    MilkCard(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusMark(r.status), Modifier.semantics { contentDescription = markDesc },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    r.bed, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleMedium,
                    color = statusColor(r.status),
                )
                Text(
                    r.babyId, Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.packs, r.balance), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
