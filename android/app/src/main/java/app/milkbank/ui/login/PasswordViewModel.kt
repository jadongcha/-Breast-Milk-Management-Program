package app.milkbank.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.core.ApiResult
import app.milkbank.data.AuthRepository
import app.milkbank.session.SessionManager
import app.milkbank.ui.Patients
import app.milkbank.ui.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasswordState(
    val loading: Boolean = false,
    val error: String? = null,
    val next: Route? = null,
)

class PasswordViewModel(
    private val auth: AuthRepository,
    private val session: SessionManager,
    private val mismatchMessage: String,
    private val unexpectedError: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(PasswordState())
    val state: StateFlow<PasswordState> = _state.asStateFlow()

    fun clearError() = _state.update { it.copy(error = null) }
    fun onNavigated() = _state.update { it.copy(next = null) }

    fun submit(current: String, new: String, confirm: String) {
        if (_state.value.loading || current.isEmpty() || new.isEmpty() || confirm.isEmpty()) return
        if (new != confirm) {
            _state.update { it.copy(error = mismatchMessage) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val r = auth.changePassword(current, new)) {
                    is ApiResult.Ok -> {
                        // The repository does not touch the session; clear the forced-change flag here.
                        session.state.value?.let { s -> session.updateStaff(s.staff.copy(mustChangePassword = false)) }
                        _state.update { it.copy(loading = false, next = Patients) }
                    }
                    is ApiResult.Err -> _state.update { it.copy(loading = false, error = r.error.message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = unexpectedError) }
            }
        }
    }

    /** Leaves the forced flow: ends the session (MainActivity then shows Login). */
    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}
