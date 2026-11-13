package app.milkbank.ui

import app.milkbank.api.StaffDto
import app.milkbank.server.ServerConfig
import app.milkbank.session.Session
import app.milkbank.station.StationConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigClearedTest {
    private val c = ServerConfig("https://x", "p")
    @Test fun clearedGoesToRegister() = assertEquals(true, configClearedNeedsRegister(c, null))
    @Test fun firstLoadNullDoesNot() = assertEquals(false, configClearedNeedsRegister(null, null))
    @Test fun registeringDoesNot() = assertEquals(false, configClearedNeedsRegister(null, c))
    @Test fun changedServerDoesNot() = assertEquals(false, configClearedNeedsRegister(c, ServerConfig("https://y", "q")))
}

class StartDestinationTest {
    private val config = ServerConfig("https://10.0.0.1:8443", "pin")
    private fun session(mustChange: Boolean) =
        Session("t", StaffDto(1, "kim", "김간호", "nurse", true, mustChange))

    @Test fun noConfig_register() = assertEquals(Register, startDestination(null, null, null))
    @Test fun noSession_login() = assertEquals(Login, startDestination(config, null, null))
    @Test fun mustChange_password() = assertEquals(Password, startDestination(config, session(true), null))
    private val station = StationConfig("tok", "신생아실 A")

    @Test fun noConfig_registerEvenWithStation() = assertEquals(Register, startDestination(null, null, station))

    @Test fun startDestination_stationMode() {
        assertEquals(GuardianHome, startDestination(config, null, station))
        assertEquals(GuardianHome, startDestination(config, session(false), station))
        assertEquals(GuardianHome, startDestination(config, session(true), station))
    }

    @Test fun guardRoute_stationMode() {
        assertEquals(true, stationModeRedirect(true, routeIsGuardian = false)) // Patients, PatientDetail, Login, ...
        assertEquals(false, stationModeRedirect(true, routeIsGuardian = true))
        assertEquals(false, stationModeRedirect(false, routeIsGuardian = false))
        assertEquals(false, stationModeRedirect(false, routeIsGuardian = true))
    }

    @Test fun stationReleased_onlyWhenStationDisappearsWithServerStillRegistered() {
        assertEquals(true, stationReleased(station, null, hasConfig = true))
        assertEquals(false, stationReleased(station, null, hasConfig = false)) // server cleared: Register wins
        assertEquals(false, stationReleased(null, null, hasConfig = true))
        assertEquals(false, stationReleased(null, station, hasConfig = true))
        assertEquals(false, stationReleased(station, station, hasConfig = true))
    }

    @Test fun otherwise_patients() = assertEquals(Patients, startDestination(config, session(false), null))

    @Test
    fun loginRedirectOnlyWhenConfiguredWithoutSessionOnProtectedRoute() {
        val sess = Session("t", app.milkbank.api.StaffDto(1, "u", "n", "nurse", true, false))
        assertEquals(true, needsLoginRedirect(null, hasConfig = true, routeIsPublic = false))
        assertEquals(false, needsLoginRedirect(null, hasConfig = true, routeIsPublic = true))
        assertEquals(false, needsLoginRedirect(null, hasConfig = false, routeIsPublic = false))
        assertEquals(false, needsLoginRedirect(sess, hasConfig = true, routeIsPublic = false))
    }
}

class GeneralGuardianRouteTest {
    @Test fun startDestination_generalGuardianIsPublic() {
        assertEquals(false, needsLoginRedirect(session = null, hasConfig = true, routeIsPublic = true))
        assertEquals(true, publicRoute(isRegister = false, isLogin = false, isGuardianGeneral = true))
        assertEquals(true, publicRoute(isRegister = true, isLogin = false, isGuardianGeneral = false))
        assertEquals(true, publicRoute(isRegister = false, isLogin = true, isGuardianGeneral = false))
        assertEquals(false, publicRoute(isRegister = false, isLogin = false, isGuardianGeneral = false))
    }

    @Test fun stationMode_redirectsGeneralGuardian() {
        // GuardianGeneral is not the station guardian route (GuardianHome).
        assertEquals(true, stationModeRedirect(true, routeIsGuardian = false))
        assertEquals(false, stationModeRedirect(false, routeIsGuardian = false))
    }
}
