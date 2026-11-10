package app.milkbank.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Probes the server every [interval] between start() and stop(). */
class ConnectionMonitor(
    private val scope: CoroutineScope,
    private val interval: Duration = 30.seconds,
    /** null = no server registered (report online, no probing); a new value restarts probing at once. */
    private val serverKey: Flow<Any?> = flowOf(Unit),
    private val probe: suspend () -> Boolean,
) {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            serverKey.collectLatest { key ->
                if (key == null) {
                    _online.value = true
                    return@collectLatest
                }
                while (isActive) {
                    _online.value = try {
                        probe()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    delay(interval)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
