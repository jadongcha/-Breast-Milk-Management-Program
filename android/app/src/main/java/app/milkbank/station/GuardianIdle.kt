package app.milkbank.station

import app.milkbank.core.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Guardian inactivity timer. Armed while a guardian is typing or signed in; when armed and the last touch is
 * [timeout] or more ago, [expired] is bumped and the timer disarms itself. Boundary: exactly [timeout] expires.
 */
class GuardianIdle(
    private val clock: Clock,
    timeout: kotlin.time.Duration = 60.seconds,
) {
    private val timeout: Duration = timeout.toJavaDuration()
    private val lock = Any()
    private var armed = false
    private var lastTouch: Instant = clock.now()
    private val _expired = MutableStateFlow(0L)

    /** Increases by one each time the timer runs out. */
    val expired: StateFlow<Long> = _expired.asStateFlow()

    fun arm() = synchronized(lock) {
        armed = true
        lastTouch = clock.now()
    }

    fun disarm() = synchronized(lock) { armed = false }

    fun touch() = synchronized(lock) { lastTouch = clock.now() }

    fun check() {
        val fire = synchronized(lock) {
            if (armed && !Duration.between(lastTouch, clock.now()).minus(timeout).isNegative) {
                armed = false
                true
            } else {
                false
            }
        }
        if (fire) _expired.update { it + 1 }
    }
}
