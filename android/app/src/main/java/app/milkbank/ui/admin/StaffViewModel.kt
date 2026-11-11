package app.milkbank.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.StaffDto
import app.milkbank.core.ApiResult
import app.milkbank.data.StaffRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StaffActionKind { ACTIVATE, DEACTIVATE, RESET }

/** An action waiting for confirmation; [username] is only for the confirm text. */
data class StaffAction(val id: Int, val username: String, val kind: StaffActionKind)

/** Shown once. Never logged or saved; toString deliberately omits the password. */
class TempPassword(val username: String, val password: String) {
    override fun equals(other: Any?) = other is TempPassword && other.username == username && other.password == password
    override fun hashCode() = 31 * username.hashCode() + password.hashCode()
    override fun toString() = "TempPassword(username=$username)"
}

private val ROLES = setOf("admin", "nurse")

class StaffViewModel(
    private val repo: () -> StaffRepository,
    private val unexpectedError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _list = MutableStateFlow<List<StaffDto>>(emptyList())
    val list: StateFlow<List<StaffDto>> = _list.asStateFlow()
    private val _pending = MutableStateFlow<StaffAction?>(null)
    val pending: StateFlow<StaffAction?> = _pending.asStateFlow()
    private val _tempPassword = MutableStateFlow<TempPassword?>(null)
    val tempPassword: StateFlow<TempPassword?> = _tempPassword.asStateFlow()
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _addOpen = MutableStateFlow(false)
    val addOpen: StateFlow<Boolean> = _addOpen.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            try {
                when (val r = repo().list()) {
                    is ApiResult.Ok -> {
                        _list.value = r.value
                        _error.value = null
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            }
        }
    }

    fun openAdd() {
        if (_submitting.value) return
        _error.value = null
        _addOpen.value = true
    }

    fun closeAdd() {
        if (_submitting.value) return
        _error.value = null
        _addOpen.value = false
    }

    fun create(username: String, name: String, role: String) {
        if (role !in ROLES) return
        if (!_submitting.compareAndSet(false, true)) return
        _error.value = null
        viewModelScope.launch {
            try {
                when (val r = repo().create(username, name, role)) {
                    is ApiResult.Ok -> {
                        _list.update { it + r.value.staff }
                        _addOpen.value = false
                        _tempPassword.value = TempPassword(r.value.staff.username, r.value.tempPassword)
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _submitting.value = false
            }
        }
    }

    fun requestSetActive(id: Int, active: Boolean) =
        request(id, if (active) StaffActionKind.ACTIVATE else StaffActionKind.DEACTIVATE)

    fun requestResetPassword(id: Int) = request(id, StaffActionKind.RESET)

    private fun request(id: Int, kind: StaffActionKind) {
        if (_submitting.value) return
        val s = _list.value.firstOrNull { it.id == id } ?: return
        _error.value = null
        _pending.value = StaffAction(id, s.username, kind)
    }

    fun dismissPending() {
        if (!_submitting.value) _pending.value = null
    }

    fun confirm() {
        val p = _pending.value ?: return
        if (!_submitting.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (p.kind) {
                    StaffActionKind.RESET -> when (val r = repo().resetPassword(p.id)) {
                        is ApiResult.Ok -> _tempPassword.value = TempPassword(p.username, r.value.tempPassword)
                        is ApiResult.Err -> _error.value = r.error.message
                    }
                    StaffActionKind.ACTIVATE, StaffActionKind.DEACTIVATE -> {
                        val active = p.kind == StaffActionKind.ACTIVATE
                        when (val r = repo().setActive(p.id, active)) {
                            is ApiResult.Ok -> _list.update { l -> l.map { if (it.id == p.id) r.value else it } }
                            is ApiResult.Err -> _error.value = r.error.message
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _pending.value = null
                _submitting.value = false
            }
        }
    }

    /** Called when the one-time dialog is dismissed: the password is gone for good. */
    fun dismissTempPassword() {
        _tempPassword.value = null
    }
}
