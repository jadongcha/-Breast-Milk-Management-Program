package app.milkbank.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.core.ApiResult
import app.milkbank.data.AuthRepository
import app.milkbank.session.SessionManager
import app.milkbank.ui.Password
import app.milkbank.ui.Patients
import app.milkbank.ui.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val next: Route? = null,
) {
    val canSubmit get() = !loading && username.isNotBlank() && password.isNotEmpty()
}

class LoginViewModel(
    private val auth: AuthRepository,
    private val session: SessionManager,
    private val unexpectedError: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onUsernameChange(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onNavigated() = _state.update { it.copy(next = null) }

    fun submit() {
        val cur = _state.value
        if (!cur.canSubmit) return
        _state.update { it.copy(loading = true, error = null) } // blocks a second submit at once
        val username = cur.username.trim()
        val password = cur.password
        viewModelScope.launch {
            try {
                when (val r = auth.login(username, password)) {
                    is ApiResult.Ok -> {
                        session.start(r.value.token, r.value.staff)
                        val next = if (r.value.staff.mustChangePassword || r.value.mustChangePassword) Password else Patients
                        _state.update { it.copy(loading = false, password = "", next = next) }
                    }
                    is ApiResult.Err ->
                        _state.update { it.copy(loading = false, password = "", error = r.error.message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, password = "", error = unexpectedError) }
            }
        }
    }
}
