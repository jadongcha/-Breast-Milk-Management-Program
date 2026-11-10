package app.milkbank.session

import app.milkbank.api.StaffDto
import app.milkbank.core.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class IdleWatcherTest {
    private val staff = StaffDto(1, "kim", "김간호", "nurse", true, false)
    private val clock = FakeClock(Instant.parse("2026-11-10T00:00:00Z"))
    private val session = SessionManager(clock)
    private val watcher = IdleWatcher(clock, session)

    private fun login() = session.start("tok", staff)

    @Test
    fun justBeforeTimeout_staysLoggedIn() {
        login()
        clock.advance(Duration.ofMinutes(9).plusSeconds(59))
        watcher.check()
        assertNotNull(session.state.value)
    }

    @Test
    fun atTimeout_logsOutWithIdle() {
        login()
        clock.advance(Duration.ofMinutes(10))
        watcher.check()
        assertNull(session.state.value)
        assertEquals(LogoutReason.IDLE, session.logoutReason.value)
    }

    @Test
    fun touchExtends() {
        login()
        clock.advance(Duration.ofMinutes(9))
        watcher.touch()
        clock.advance(Duration.ofMinutes(9))
        watcher.check()
        assertNotNull(session.state.value)
        clock.advance(Duration.ofMinutes(1))
        watcher.check()
        assertNull(session.state.value)
    }

    @Test
    fun noSession_nothingHappens() {
        clock.advance(Duration.ofMinutes(30))
        watcher.check()
        assertNull(session.logoutReason.value)
    }

    @Test
    fun resumeAfterIdle_logsOut() {
        login()
        clock.advance(Duration.ofMinutes(11))
        watcher.check()
        assertEquals(LogoutReason.IDLE, session.logoutReason.value)
        assertNull(session.state.value)
    }
}
