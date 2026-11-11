package app.milkbank.ui

import app.milkbank.server.ServerConfig
import app.milkbank.session.Session
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
@Serializable data object GuardianHome : Route

/** True when a registered server has just been removed, so the app must go back to registration. */
fun configClearedNeedsRegister(previous: ServerConfig?, current: ServerConfig?): Boolean =
    previous != null && current == null

fun startDestination(config: ServerConfig?, session: Session?): Route = when {
    config == null -> Register
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
