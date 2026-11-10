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

fun startDestination(config: ServerConfig?, session: Session?): Route = when {
    config == null -> Register
    session == null -> Login
    session.staff.mustChangePassword -> Password
    else -> Patients
}
