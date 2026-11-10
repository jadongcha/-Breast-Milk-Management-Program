package app.milkbank.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeClockTest {
    @Test
    fun advanceMovesNow() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        clock.advance(Duration.ofMinutes(10))
        assertEquals(Instant.parse("2026-01-01T00:10:00Z"), clock.now())
    }
}
