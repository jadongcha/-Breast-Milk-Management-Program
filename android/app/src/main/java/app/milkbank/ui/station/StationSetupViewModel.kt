package app.milkbank.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.core.ApiResult
import app.milkbank.data.StationRepository
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import app.milkbank.station.StationConfig
import app.milkbank.station.StationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SetupState {
    data object Idle : SetupState
    data class Confirming(val name: String) : SetupState
    data object Submitting : SetupState
    data object Done : SetupState
    data class Error(val message: String) : SetupState
}

/** Registers this tablet as a submission station: create, save the token, then end the staff session. */
class StationSetupViewModel(
    private val stations: () -> StationRepository,
    private val store: StationStore,
    private val session: SessionManager,
    private val unexpectedError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /** A blank name goes nowhere; otherwise ask for confirmation. */
    fun onName(name: String) {
        if (_state.value is SetupState.Submitting || _state.value == SetupState.Done) return
        val n = name.trim()
        _state.value = if (n.isEmpty()) SetupState.Idle else SetupState.Confirming(n)
    }

    /** Back to the start (cancel, or dismiss an error). */
    fun reset() {
        if (_state.value is SetupState.Submitting || _state.value == SetupState.Done) return
        _state.value = SetupState.Idle
    }

    fun confirm() {
        val c = _state.value as? SetupState.Confirming ?: return
        if (!_state.compareAndSet(c, SetupState.Submitting)) return
        viewModelScope.launch {
            try {
                when (val r = stations().create(c.name)) {
                    is ApiResult.Ok -> {
                        store.save(StationConfig(r.value.stationToken, r.value.station.name))
                        session.end(LogoutReason.USER)
                        // The user is going to the guardian home, not the login screen: drop the logout
                        // reason right away so the root's logout handling does not redirect to Login.
                        session.consumeLogoutReason()
                        _state.value = SetupState.Done
                    }
                    is ApiResult.Err -> _state.value = SetupState.Error(r.error.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _state.value = SetupState.Error(unexpectedError)
            }
        }
    }
}
