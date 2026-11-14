package app.milkbank.session

import app.milkbank.core.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Logs out when no touch happened for the session's idle minutes (set by the server, default 10).
 * Boundary: idle exactly at that time logs out.
 */
class IdleWatcher(
    private val clock: Clock,
    private val session: SessionManager,
) {
    private val lastTouch = AtomicReference<Instant>(clock.now())

    fun touch() {
        lastTouch.set(clock.now())
    }

    fun check() {
        val idleMinutes = session.state.value?.idleMinutes ?: return
        val timeout = Duration.ofMinutes(idleMinutes.toLong())
        if (!Duration.between(lastTouch.get(), clock.now()).minus(timeout).isNegative) {
            session.end(LogoutReason.IDLE)
        }
    }
}
