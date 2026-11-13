package app.milkbank.ui.guardian

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.milkbank.AppContainer
import app.milkbank.R
import app.milkbank.core.ElapsedClock
import app.milkbank.ui.logUnexpected

/**
 * Guardian flow on a general (shared) hospital device: public route, no station token. Whenever the session
 * ends (finish, 60 s idle, 401) or the parent goes back, everything is cleared and [onExit] returns to the choice.
 */
@Composable
fun GuardianGeneralRoute(container: AppContainer, onExit: () -> Unit) {
    val unexpectedText = stringResource(R.string.network_error)
    val submitUncertain = stringResource(R.string.guardian_submit_uncertain)
    val sessionExpiredText = stringResource(R.string.guardian_session_expired)
    // Its own instance (key), never the station one; cleared with this destination (onCleared logs out a live token).
    val vm: GuardianViewModel = viewModel(
        key = "guardian-general",
        factory = viewModelFactory {
            initializer {
                GuardianViewModel(
                    { container.guardian }, { null }, container.guardianIdle,
                    {}, // station_invalid cannot come back without a station token
                    submitUncertain, unexpectedText, sessionExpiredText, ElapsedClock,
                    logUnexpected("guardian flow failed"),
                )
            }
        },
    )

    val ended by vm.sessionEnded.collectAsStateWithLifecycle()
    LaunchedEffect(ended) { if (ended > 0L) onExit() }

    val leave = { vm.finish(); onExit() }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = leave, modifier = Modifier.padding(start = 8.dp, top = 8.dp).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.login_back_to_choice))
        }
        GuardianFlow(vm, stringResource(R.string.guardian_general_header))
    }
}
