package app.milkbank.core

import java.time.Duration
import java.time.Instant

class FakeClock(var current: Instant) : Clock {
    override fun now(): Instant = current

    fun advance(d: Duration) {
        current = current.plus(d)
    }
}
