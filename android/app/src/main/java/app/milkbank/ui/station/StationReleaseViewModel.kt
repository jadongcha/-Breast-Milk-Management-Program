package app.milkbank.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.core.ApiResult
import app.milkbank.core.isStationInvalid
import app.milkbank.data.AuthRepository
import app.milkbank.data.StationRepository
import app.milkbank.session.SessionManager
import app.milkbank.station.GuardianIdle
import app.milkbank.station.StationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface ReleaseState {
    data class Login(val error: String? = null, val loading: Boolean = false) : ReleaseState
    data object Ready : ReleaseState
    data object Releasing : ReleaseState
    data object Done : ReleaseState

    /** Closed by the idle timeout while signed in; the admin session is already ended. */
    data object TimedOut : ReleaseState
    data class Error(val message: String) : ReleaseState
}

/**
 * Admin release of station mode: an admin signs in (a staff session lives only while this dialog is open),
 * then the server is told and the saved station info is cleared.
 */
class StationReleaseViewModel(
    private val auth: () -> AuthRepository,
    private val stations: () -> StationRepository,
    private val store: StationStore,
    private val session: SessionManager,
    private val idle: GuardianIdle,
    private val adminOnlyMessage: String,
    private val offlineMessage: String,
    private val unexpectedError: String,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<ReleaseState>(ReleaseState.Login())
    val state: StateFlow<ReleaseState> = _state.asStateFlow()

    init {
        // A new dialog always starts at the login step: a staff session left over from an earlier dialog is ended.
        if (session.state.value != null) auth().logout()
        // Signed in (ready or retrying after an error) and untouched for the idle limit: close the dialog.
        viewModelScope.launch {
            _state.collect { if (it == ReleaseState.Ready || it is ReleaseState.Error) idle.arm() else idle.disarm() }
        }
        viewModelScope.launch {
            idle.expired.drop(1).collect {
                val cur = _state.value
                if (cur == ReleaseState.Ready || cur is ReleaseState.Error) {
                    _state.value = ReleaseState.TimedOut
                    if (session.state.value != null) auth().logout()
                }
            }
        }
        // The admin session can end under us (idle logout, expired token): sign in again.
        viewModelScope.launch {
            session.state.collect { s ->
                if (s == null) {
                    val cur = _state.value
                    if (cur == ReleaseState.Ready || cur is ReleaseState.Error) _state.value = ReleaseState.Login()
                }
            }
        }
    }

    fun login(username: String, password: String) {
        val cur = _state.value as? ReleaseState.Login ?: return
        if (cur.loading || username.isBlank() || password.isEmpty()) return
        _state.value = ReleaseState.Login(loading = true)
        val name = username.trim()
        viewModelScope.launch {
            try {
                when (val r = auth().login(name, password)) {
                    is ApiResult.Ok -> {
                        // The session is needed first: the repository's logout acts on the current session.
                        session.start(r.value.token, r.value.staff, r.value.staffIdleMinutes)
                        if (r.value.staff.role == "admin") {
                            _state.value = ReleaseState.Ready
                        } else {
                            auth().logout()
                            _state.value = ReleaseState.Login(adminOnlyMessage)
                        }
                    }
                    is ApiResult.Err -> _state.value = ReleaseState.Login(r.error.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _state.value = ReleaseState.Login(unexpectedError)
            }
        }
    }

    /**
     * Releases station mode. Done once the server confirms (or already says the station is invalid), or when
     * [stationInvalid] (released from the invalid screen: the server may not know this tablet any more).
     */
    fun release(token: String, stationInvalid: Boolean) {
        val cur = _state.value
        if (cur != ReleaseState.Ready && cur !is ReleaseState.Error) return
        if (session.state.value == null) { // no admin signed in (any more): sign in first
            _state.value = ReleaseState.Login()
            return
        }
        if (!_state.compareAndSet(cur, ReleaseState.Releasing)) return
        viewModelScope.launch {
            try {
                val r = stations().releaseSelf(token)
                val cleared = stationInvalid || r is ApiResult.Ok || (r is ApiResult.Err && r.error.isStationInvalid())
                if (cleared) {
                    store.clear()
                    // A 401 may already have ended the staff session as EXPIRED; either way the user is
                    // going to the login screen without a "log in again" notice. The server hears of the logout too.
                    auth().logout()
                    session.consumeLogoutReason()
                    _state.value = ReleaseState.Done
                } else {
                    val e = (r as ApiResult.Err).error
                    _state.value = ReleaseState.Error(if (e.status == null) offlineMessage else e.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _state.value = ReleaseState.Error(unexpectedError)
            }
        }
    }

    /** Touch and clock tick from the dialog: the idle timer is this ViewModel's own, not the guardian's. */
    fun touch() = idle.touch()

    fun checkIdle() = idle.check()

    /** The admin session never outlives the dialog, even if the screen is torn down without a cancel. */
    override fun onCleared() {
        if (session.state.value != null) auth().logout()
    }

    /** Closes the dialog: the admin session ends with it. */
    fun cancel() {
        if (_state.value == ReleaseState.Releasing) return
        if (session.state.value != null) auth().logout()
    }
}
