package app.milkbank.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class FormatPendingTimeTest {
    private val now = Instant.parse("2026-11-12T05:00:00Z") // 14:00 KST on 11-12

    @Test
    fun formatPendingTime_today_afternoon() {
        assertEquals("오후 2시 13분", formatPendingTime(Instant.parse("2026-11-12T05:13:00Z"), now))
    }

    @Test
    fun formatPendingTime_today_noonAndMorning() {
        assertEquals("오후 12시 0분", formatPendingTime(Instant.parse("2026-11-12T03:00:00Z"), now))
        assertEquals("오전 9시 5분", formatPendingTime(Instant.parse("2026-11-12T00:05:00Z"), now))
    }

    @Test
    fun formatPendingTime_otherDay() {
        assertEquals("11월 10일 오후 2시 13분", formatPendingTime(Instant.parse("2026-11-10T05:13:00Z"), now))
    }

    @Test
    fun formatPendingTime_midnightIsAmTwelve_andUsesSeoulDayNotUtc() {
        // 2026-11-11T15:30Z is 00:30 KST on 11-12: same KST day as now, though a different UTC day.
        assertEquals("오전 12시 30분", formatPendingTime(Instant.parse("2026-11-11T15:30:00Z"), now))
    }

    @Test
    fun formatPendingTime_independentOfDeviceZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("오후 2시 13분", formatPendingTime(Instant.parse("2026-11-12T05:13:00Z"), now))
            assertEquals("11월 10일 오후 2시 13분", formatPendingTime(Instant.parse("2026-11-10T05:13:00Z"), now))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}

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
