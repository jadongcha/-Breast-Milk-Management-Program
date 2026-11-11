package app.milkbank.session

import app.milkbank.api.StaffDto
import app.milkbank.core.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

enum class LogoutReason { USER, EXPIRED, IDLE }

data class Session(val token: String, val staff: StaffDto)

/** In-memory only: the token is never written to disk. */
class SessionManager(@Suppress("unused") private val clock: Clock) {
    private val _state = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = _state.asStateFlow()

    // Consumable state (not events): survives periods with no collector.
    private val _logoutReason = MutableStateFlow<LogoutReason?>(null)
    val logoutReason: StateFlow<LogoutReason?> = _logoutReason.asStateFlow()

    // True once the server demanded a password change; the staff flag alone may be stale.
    @Volatile var passwordChangeForced: Boolean = false
        private set

    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    fun start(token: String, staff: StaffDto) {
        passwordChangeForced = false
        _state.value = Session(token, staff)
    }

    /** Replaces the logged-in staff (e.g. after a password change), keeping the token; no-op when logged out. */
    fun updateStaff(staff: StaffDto) {
        if (!staff.mustChangePassword) passwordChangeForced = false
        _state.update { cur -> cur?.copy(staff = staff) }
    }

    /** Ends the session and records [reason]; a no-op when nobody is logged in. */
    fun end(reason: LogoutReason) {
        if (_state.getAndUpdate { null } != null) _logoutReason.value = reason
    }

    /** Ends the session only if it is still the one holding [token] (guards against stale 401s). */
    fun endIfToken(token: String, reason: LogoutReason) {
        while (true) {
            val cur = _state.value
            if (cur == null || cur.token != token) return
            if (_state.compareAndSet(cur, null)) {
                _logoutReason.value = reason
                return
            }
        }
    }

    fun consumeLogoutReason() {
        _logoutReason.value = null
    }

    fun requirePasswordChange() {
        passwordChangeForced = true
        _passwordRequired.value = true
    }

    fun consumePasswordRequired() {
        _passwordRequired.value = false
    }
}
