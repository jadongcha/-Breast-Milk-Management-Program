package app.milkbank.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class TimeTest {
    @Test
    fun formatsInSeoulRegardlessOfDefaultZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("2026-11-11 00:30", formatKst(Instant.parse("2026-11-10T15:30:00Z")))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
