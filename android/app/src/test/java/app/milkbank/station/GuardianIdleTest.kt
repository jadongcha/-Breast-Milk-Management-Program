package app.milkbank.station

import app.milkbank.core.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GuardianIdleTest {
    private val clock = FakeClock(Instant.parse("2026-11-12T00:00:00Z"))
    private val idle = GuardianIdle(clock)

    @Test fun at59s_stays() {
        idle.arm()
        clock.advance(Duration.ofSeconds(59))
        idle.check()
        assertEquals(0L, idle.expired.value)
    }

    @Test fun at60s_expiresOnce() {
        idle.arm()
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        assertEquals(1L, idle.expired.value)
        clock.advance(Duration.ofSeconds(120))
        idle.check() // disarmed by the expiry
        assertEquals(1L, idle.expired.value)
    }

    @Test fun touchRestartsCount() {
        idle.arm()
        clock.advance(Duration.ofSeconds(59))
        idle.touch()
        clock.advance(Duration.ofSeconds(59))
        idle.check()
        assertEquals(0L, idle.expired.value)
        clock.advance(Duration.ofSeconds(1))
        idle.check()
        assertEquals(1L, idle.expired.value)
    }

    @Test fun notArmed_neverExpires() {
        clock.advance(Duration.ofMinutes(10))
        idle.check()
        assertEquals(0L, idle.expired.value)
    }

    @Test fun disarm_stopsExpiry() {
        idle.arm()
        idle.disarm()
        clock.advance(Duration.ofMinutes(10))
        idle.check()
        assertEquals(0L, idle.expired.value)
    }
}
