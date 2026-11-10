package app.milkbank.ui.register

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.R
import app.milkbank.core.ApiResult
import app.milkbank.server.QrParse
import app.milkbank.server.RegistrationDecision
import app.milkbank.server.ServerConfig
import app.milkbank.server.decideRegistration
import app.milkbank.server.parseQr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RegisterState {
    data object Scanning : RegisterState
    data object Checking : RegisterState
    data class Error(@StringRes val messageRes: Int) : RegisterState
    data class ConfirmDifferentServer(val config: ServerConfig) : RegisterState
    data object Done : RegisterState
}

class RegisterViewModel(
    private val health: suspend (ServerConfig) -> ApiResult<Unit>,
    private val currentConfig: suspend () -> ServerConfig?,
    private val save: suspend (ServerConfig) -> Unit,
    private val endSession: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<RegisterState>(RegisterState.Scanning)
    val state: StateFlow<RegisterState> = _state.asStateFlow()

    fun onScanned(raw: String) {
        // One scan at a time; Error/Confirm must be dismissed before scanning again.
        if (_state.value != RegisterState.Scanning) return
        val config = when (val parsed = parseQr(raw)) {
            is QrParse.Ok -> parsed.config
            QrParse.NotServerQr -> {
                _state.value = RegisterState.Error(R.string.register_not_qr)
                return
            }
        }
        _state.value = RegisterState.Checking
        viewModelScope.launch {
            if (health(config) !is ApiResult.Ok) {
                _state.value = RegisterState.Error(R.string.register_unreachable)
                return@launch
            }
            _state.value = when (decideRegistration(currentConfig(), config)) {
                RegistrationDecision.SAVE -> {
                    save(config)
                    RegisterState.Done
                }
                RegistrationDecision.CONFIRM_DIFFERENT_SERVER -> RegisterState.ConfirmDifferentServer(config)
            }
        }
    }

    fun confirmDifferentServer() {
        val pending = _state.value as? RegisterState.ConfirmDifferentServer ?: return
        _state.value = RegisterState.Checking // blocks a second tap while saving
        viewModelScope.launch {
            save(pending.config)
            endSession()
            _state.value = RegisterState.Done
        }
    }

    fun dismiss() {
        val s = _state.value
        if (s is RegisterState.Error || s is RegisterState.ConfirmDifferentServer) {
            _state.value = RegisterState.Scanning
        }
    }
}
