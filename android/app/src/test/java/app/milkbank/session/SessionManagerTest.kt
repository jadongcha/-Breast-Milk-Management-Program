package app.milkbank.session

import app.cash.turbine.test
import app.milkbank.api.StaffDto
import app.milkbank.core.FakeClock
import kotlinx.coroutines.test.runTest
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
    fun endEmitsReason() = runTest {
        val m = manager()
        m.events.test {
            m.start("tok", staff)
            m.end(LogoutReason.EXPIRED)
            assertEquals(LogoutReason.EXPIRED, awaitItem())
        }
    }

    @Test
    fun endWithoutSessionEmitsNothing() = runTest {
        val m = manager()
        m.events.test {
            m.end(LogoutReason.EXPIRED)
            expectNoEvents()
        }
    }
}
