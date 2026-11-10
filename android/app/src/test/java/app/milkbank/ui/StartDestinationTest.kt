package app.milkbank.ui

import app.milkbank.api.StaffDto
import app.milkbank.server.ServerConfig
import app.milkbank.session.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {
    private val config = ServerConfig("https://10.0.0.1:8443", "pin")
    private fun session(mustChange: Boolean) =
        Session("t", StaffDto(1, "kim", "김간호", "nurse", true, mustChange))

    @Test fun noConfig_register() = assertEquals(Register, startDestination(null, null))
    @Test fun noSession_login() = assertEquals(Login, startDestination(config, null))
    @Test fun mustChange_password() = assertEquals(Password, startDestination(config, session(true)))
    @Test fun otherwise_patients() = assertEquals(Patients, startDestination(config, session(false)))
}
