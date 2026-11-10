package app.milkbank.session

import app.milkbank.api.StaffDto
import app.milkbank.core.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

enum class LogoutReason { USER, EXPIRED, IDLE }

data class Session(val token: String, val staff: StaffDto)

/** In-memory only: the token is never written to disk. */
class SessionManager(@Suppress("unused") private val clock: Clock) {
    private val _state = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LogoutReason>(extraBufferCapacity = 8)
    val events: SharedFlow<LogoutReason> = _events.asSharedFlow()

    private val _passwordRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val passwordRequired: SharedFlow<Unit> = _passwordRequired.asSharedFlow()

    fun start(token: String, staff: StaffDto) {
        _state.value = Session(token, staff)
    }

    /** Ends the session and emits [reason]; a no-op when nobody is logged in. */
    fun end(reason: LogoutReason) {
        if (_state.getAndUpdate { null } != null) _events.tryEmit(reason)
    }

    fun requirePasswordChange() {
        _passwordRequired.tryEmit(Unit)
    }
}
