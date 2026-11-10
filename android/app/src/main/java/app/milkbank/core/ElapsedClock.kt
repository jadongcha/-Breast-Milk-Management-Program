package app.milkbank.core

import java.time.Instant

/** Monotonic clock (ms since boot, includes deep sleep): immune to wall-clock changes. Only for measuring intervals. */
object ElapsedClock : Clock {
    override fun now(): Instant = Instant.ofEpochMilli(android.os.SystemClock.elapsedRealtime())
}
