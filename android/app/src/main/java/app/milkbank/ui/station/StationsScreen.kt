package app.milkbank.ui.station

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.api.StationDto
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.MilkbankColors
import app.milkbank.ui.patients.MilkCard

private val MinButton = Modifier.heightIn(min = 56.dp)

/** Station list: name and active state; an active one can be deactivated after a confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsScreen(viewModel: StationsViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val online = LocalOnline.current
    var pendingId by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.stations_title)) },
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(items, key = { it.id }) { s ->
                    StationRow(s, online && !busy) { pendingId = s.id }
                }
            }
        }
    }

    val target = items.firstOrNull { it.id == pendingId }
    if (target != null) {
        IdleAwareDialog(
            onDismissRequest = { pendingId = null },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = Color.White) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.station_deactivate_confirm, target.name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { pendingId = null }, modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = { viewModel.deactivate(target.id); pendingId = null },
                            enabled = online && !busy,
                            modifier = Modifier.weight(1f).then(MinButton),
                        ) { Text(stringResource(R.string.confirm)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(s: StationDto, actionsEnabled: Boolean, onDeactivate: () -> Unit) {
    MilkCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(if (s.active) R.string.station_active else R.string.station_inactive),
                    color = if (s.active) MaterialTheme.colorScheme.onSurfaceVariant else MilkbankColors.Error,
                )
            }
            if (s.active) {
                OutlinedButton(
                    onClick = onDeactivate, enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth().then(MinButton),
                ) { Text(stringResource(R.string.station_deactivate)) }
            }
        }
    }
}
