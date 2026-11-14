package app.milkbank.ui

import app.milkbank.server.ServerConfig
import app.milkbank.session.Session
import app.milkbank.station.StationConfig
import kotlinx.serialization.Serializable

sealed interface Route

@Serializable data object Register : Route
@Serializable data object Login : Route
@Serializable data object Password : Route
@Serializable data object Patients : Route
@Serializable data class PatientDetail(val id: Int) : Route
@Serializable data class PatientForm(val id: Int? = null) : Route
@Serializable data object Staff : Route
@Serializable data object Stations : Route
@Serializable data object AdminSettings : Route
@Serializable data object GuardianHome : Route

/** Guardian flow on a general hospital device: public (no staff session), no station token. */
@Serializable data object GuardianGeneral : Route

/** True when a registered server has just been removed, so the app must go back to registration. */
fun configClearedNeedsRegister(previous: ServerConfig?, current: ServerConfig?): Boolean =
    previous != null && current == null

fun startDestination(config: ServerConfig?, session: Session?, station: StationConfig?): Route = when {
    config == null -> Register
    station != null -> GuardianHome
    session == null -> Login
    session.staff.mustChangePassword -> Password
    else -> Patients
}

/**
 * Guard for restored back stacks (process death leaves screens but no in-memory session):
 * a registered app without a session may only show the public screens.
 */
fun needsLoginRedirect(session: Session?, hasConfig: Boolean, routeIsPublic: Boolean): Boolean =
    hasConfig && session == null && !routeIsPublic

/** Screens a registered app may show without a staff session. */
fun publicRoute(isRegister: Boolean, isLogin: Boolean, isGuardianGeneral: Boolean): Boolean =
    isRegister || isLogin || isGuardianGeneral

/**
 * Station mode shows the guardian screen only: any other route (also a restored back stack after process
 * death) goes to GuardianHome, clearing the stack. In station mode the login redirect is not applied.
 */
fun stationModeRedirect(stationMode: Boolean, routeIsGuardian: Boolean): Boolean =
    stationMode && !routeIsGuardian

/** True when station mode was just released (station info gone) while a server is still registered. */
fun stationReleased(previous: StationConfig?, current: StationConfig?, hasConfig: Boolean): Boolean =
    previous != null && current == null && hasConfig
