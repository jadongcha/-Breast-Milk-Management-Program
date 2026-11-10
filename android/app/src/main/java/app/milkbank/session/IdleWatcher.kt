package app.milkbank.session

import app.milkbank.core.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Logs out when no touch happened for [timeout]. Boundary: idle exactly at [timeout]. */
class IdleWatcher(
    private val clock: Clock,
    private val session: SessionManager,
    timeout: kotlin.time.Duration = 10.minutes,
) {
    private val timeout: Duration = timeout.toJavaDuration()
    private val lastTouch = AtomicReference<Instant>(clock.now())

    fun touch() {
        lastTouch.set(clock.now())
    }

    fun check() {
        if (session.state.value == null) return
        if (!Duration.between(lastTouch.get(), clock.now()).minus(timeout).isNegative) {
            session.end(LogoutReason.IDLE)
        }
    }
}
