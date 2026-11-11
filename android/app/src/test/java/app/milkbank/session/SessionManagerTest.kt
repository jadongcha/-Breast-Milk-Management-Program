package app.milkbank.session

import app.milkbank.api.StaffDto
import app.milkbank.core.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SessionManagerTest {
    private val staff = StaffDto(1, "kim", "김간호", "nurse", true, false)
    private fun manager() = SessionManager(FakeClock(Instant.parse("2026-11-10T00:00:00Z")))

    @Test
    fun startAndEnd() {
        val m = manager()
        assertNull(m.state.value)
        m.start("tok", staff)
        assertEquals(Session("tok", staff), m.state.value)
        m.end(LogoutReason.USER)
        assertNull(m.state.value)
    }

    @Test
    fun logoutReasonSurvivesWithoutCollectorAndIsConsumable() {
        val m = manager()
        m.start("tok", staff)
        m.end(LogoutReason.EXPIRED)
        assertEquals(LogoutReason.EXPIRED, m.logoutReason.value)
        m.consumeLogoutReason()
        assertNull(m.logoutReason.value)
    }

    @Test
    fun endWithoutSessionRecordsNothing() {
        val m = manager()
        m.end(LogoutReason.EXPIRED)
        assertNull(m.logoutReason.value)
    }

    @Test
    fun passwordRequiredIsConsumable() {
        val m = manager()
        m.requirePasswordChange()
        assertEquals(true, m.passwordRequired.value)
        m.consumePasswordRequired()
        assertEquals(false, m.passwordRequired.value)
    }

    @Test
    fun endIfTokenIgnoresStaleToken() {
        val m = manager()
        m.start("A", staff)
        m.start("B", staff)
        m.endIfToken("A", LogoutReason.EXPIRED)
        assertEquals("B", m.state.value?.token)
        assertNull(m.logoutReason.value)
        m.endIfToken("B", LogoutReason.EXPIRED)
        assertNull(m.state.value)
        assertEquals(LogoutReason.EXPIRED, m.logoutReason.value)
    }

    @Test
    fun updateStaffKeepsTokenAndIgnoresWhenLoggedOut() {
        val m = manager()
        m.updateStaff(staff)
        assertNull(m.state.value)
        m.start("tok", staff.copy(mustChangePassword = true))
        m.updateStaff(staff.copy(mustChangePassword = false))
        assertEquals(Session("tok", staff), m.state.value)
    }

    @Test
    fun passwordChangeForcedByServerUntilStaffUpdated() {
        val m = manager()
        m.start("tok", staff)
        assertEquals(false, m.passwordChangeForced)
        m.requirePasswordChange()
        assertEquals(true, m.passwordChangeForced)
        m.updateStaff(staff.copy(mustChangePassword = false))
        assertEquals(false, m.passwordChangeForced)
    }
}
