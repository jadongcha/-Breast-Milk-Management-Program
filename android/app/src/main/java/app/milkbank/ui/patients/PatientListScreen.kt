package app.milkbank.ui.patients

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.api.PatientDto
import app.milkbank.ui.MilkbankColors
import app.milkbank.ui.components.IdleTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PENDING_REFRESH_MS = 30_000L

/** Home body (spec 3.4): result banner, pending card, all-babies list, ID search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientListContent(
    viewModel: PatientListViewModel,
    pendingViewModel: PendingViewModel,
    onSelect: (PatientDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val match by viewModel.match.collectAsStateWithLifecycle()
    val listMatch by viewModel.listMatch.collectAsStateWithLifecycle()
    val last by viewModel.lastResult.collectAsStateWithLifecycle()
    var pendingOpen by rememberSaveable { mutableStateOf(true) }

    val pendingItems by pendingViewModel.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Coming back from the detail screen (or the app) reloads the list and the pending table;
    // the pending table then refreshes every 30 s, only while this home is resumed.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        pendingViewModel.refresh()
        val ticker = scope.launch {
            while (true) {
                delay(PENDING_REFRESH_MS)
                pendingViewModel.refresh()
            }
        }
        onPauseOrDispose { ticker.cancel() }
    }

    val shown = when {
        listMatch is BabyMatch.Many && match is BabyMatch.Many -> (listMatch as BabyMatch.Many).patients
        expanded -> patients
        else -> emptyList()
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            last?.let { b ->
                item {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(MilkbankColors.PrimaryLight, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.result_banner, b.regNo, b.count, kindLabel(b.kind), b.balance),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MilkbankColors.Primary,
                        )
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                MilkCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.pending_count_title, pendingItems.size),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = { pendingOpen = !pendingOpen }, modifier = Modifier.heightIn(min = 56.dp)) {
                                Text(stringResource(if (pendingOpen) R.string.hide_section else R.string.show_section))
                            }
                        }
                        if (pendingOpen) {
                            PendingSection(pendingViewModel)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdleTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        label = stringResource(R.string.baby_search_label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val single = (match as? BabyMatch.Single)?.patient
                    Button(
                        onClick = { single?.let(onSelect) },
                        enabled = single != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.select_baby, single?.regNo ?: stringResource(R.string.baby_id_placeholder))) }
                    if (match is BabyMatch.None) {
                        Text(stringResource(R.string.no_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = viewModel::toggleExpanded,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.all_babies, patients.size)) }
            }
            items(shown, key = { it.id }) { p -> BabyRow(p) { onSelect(p) } }
        }
    }
}

@Composable
private fun BabyRow(p: PatientDto, onClick: () -> Unit) {
    MilkCard(Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.regNo, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.remaining_packs, p.balance),
                style = MaterialTheme.typography.titleMedium,
                color = if (p.balance == 0) MilkbankColors.Error else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (p.balance == 0) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
