package app.milkbank.ui.guardian

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.milkbank.AppContainer
import app.milkbank.R
import app.milkbank.core.ElapsedClock
import app.milkbank.station.GuardianIdle
import app.milkbank.ui.logUnexpected
import app.milkbank.ui.station.StationReleaseDialog
import app.milkbank.ui.station.StationReleaseViewModel

private const val RELEASE_HOLD_MS = 3000L

/** Station tablet home. header, hidden release area, invalid screen, guardian flow. */
@Composable
fun GuardianHomeRoute(container: AppContainer) {
    val station by container.stationStore.config.collectAsStateWithLifecycle(initialValue = null)
    val invalid by container.stationInvalid.collectAsStateWithLifecycle()
    val config = station
    if (config == null) {
        Box(Modifier.fillMaxSize()) // released a moment ago: the root is already leaving this screen
        return
    }

    val gate = remember { GuardianHomeGate({ container.stations }, container.stationInvalid) }
    var serverName by rememberSaveable { mutableStateOf<String?>(null) }
    // On open and every time the app comes back to the front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(config.token) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            gate.check(config.token)?.let { serverName = it }
        }
    }

    // Guardian session state (token, step, inputs) lives in this ViewModel only: memory, never saved.
    val submitUncertain = stringResource(R.string.guardian_submit_uncertain)
    val unexpectedText = stringResource(R.string.network_error)
    val sessionExpiredText = stringResource(R.string.guardian_session_expired)
    val currentToken by rememberUpdatedState(config.token)
    val guardianVm: GuardianViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GuardianViewModel(
                    { container.guardian }, { currentToken }, container.guardianIdle,
                    { container.stationInvalid.value = true },
                    submitUncertain, unexpectedText, sessionExpiredText, ElapsedClock,
                    logUnexpected("guardian flow failed"),
                )
            }
        },
    )

    // The gate check can flag the station invalid mid-session: drop the guardian token and inputs right away.
    LaunchedEffect(invalid) { if (invalid) guardianVm.finish() }

    // Saveable: a rotation brings the dialog back together with its ViewModel instead of orphaning the admin session.
    var releaseOpen by rememberSaveable { mutableStateOf(false) }
    var releaseRun by rememberSaveable { mutableIntStateOf(0) }
    val staffSession by container.session.state.collectAsStateWithLifecycle()
    LaunchedEffect(releaseOpen, staffSession) {
        if (shouldDropStaffSession(true, releaseOpen, staffSession != null)) container.auth.logout()
    }

    Box(Modifier.fillMaxSize()) {
        GuardianFlow(guardianVm, stringResource(R.string.guardian_station_header, serverName ?: config.name), invalid)
        // Hidden admin area: no visible hint.
        Box(
            Modifier.align(Alignment.TopStart).size(72.dp).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var released = false
                    withTimeoutOrNull(RELEASE_HOLD_MS) {
                        waitForUpOrCancellation()
                        released = true
                    }
                    if (!released) {
                        releaseRun++
                        releaseOpen = true
                    }
                }
            },
        )
    }

    if (releaseOpen) {
        val unexpected = stringResource(R.string.network_error)
        val adminOnly = stringResource(R.string.station_release_admin_only)
        val offline = stringResource(R.string.station_release_offline)
        val vm: StationReleaseViewModel = viewModel(
            key = "station-release-$releaseRun", // a fresh sign-in every time the dialog opens
            factory = viewModelFactory {
                initializer {
                    StationReleaseViewModel(
                        { container.auth }, { container.stations }, container.stationStore, container.session,
                        GuardianIdle(ElapsedClock),
                        adminOnly, offline, unexpected, logUnexpected("station release failed"),
                    )
                }
            },
        )
        StationReleaseDialog(vm, config.token, stationInvalid = invalid, onClose = { releaseOpen = false })
    }
}
