package app.milkbank.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.BabyDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.core.isStationInvalid
import app.milkbank.data.GuardianRepository
import app.milkbank.station.GuardianIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

const val GUARDIAN_MAX_PACKS = 10

/** The server ends a guardian session 60 s after its last request; ping well before that while the parent is active. */
private val KEEPALIVE_AFTER: Duration = Duration.ofSeconds(30)

sealed interface GuardianStep {
    data class Start(val phone: String, val babyId: String, val error: String?, val submitting: Boolean) : GuardianStep
    data class SelectBaby(val babies: List<BabyDto>) : GuardianStep
    data class Stock(val baby: BabyDto, val notice: String? = null) : GuardianStep
    data class PickCount(val baby: BabyDto, val count: Int?) : GuardianStep
    data class ConfirmSubmit(val baby: BabyDto, val count: Int) : GuardianStep
    data class Done(val baby: BabyDto, val count: Int, val balance: Int, val pendingCount: Int) : GuardianStep
}

/** Guardian session on a station tablet. The guardian token lives here only; logout and idle drop everything. */
class GuardianViewModel(
    private val guardian: () -> GuardianRepository,
    /** The station tablet's token; null on a general hospital device, where requests go without one. */
    private val stationToken: () -> String?,
    private val idle: GuardianIdle,
    private val onStationInvalid: () -> Unit,
    private val submitUncertain: String,
    private val unexpected: String,
    private val sessionExpired: String,
    private val clock: Clock,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val blank = GuardianStep.Start("", "", null, false)
    private val _step = MutableStateFlow<GuardianStep>(blank)
    val step: StateFlow<GuardianStep> = _step.asStateFlow()
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _sessionEnded = MutableStateFlow(0L)

    /** Bumped each time the session ends (finish, idle, 401): a general-device route returns to the choice screen. */
    val sessionEnded: StateFlow<Long> = _sessionEnded.asStateFlow()

    private var token: String? = null

    // When the server last answered a guardian request: its session timer counts from there, not from our last touch.
    private var lastRequest: Instant = clock.now()
    private val keepAliveLock = Mutex()

    // Bumped by every reset: an answer that arrives after the session ended must not touch the new state.
    private var gen = 0

    init {
        viewModelScope.launch { idle.expired.drop(1).collect { reset() } }
    }

    private fun reset() {
        val t = token
        val st = stationToken()
        token = null
        gen++
        _submitting.value = false
        idle.disarm()
        _step.value = blank
        _sessionEnded.value += 1
        if (t != null) guardian().logout(st, t)
    }

    fun finish() = reset()

    override fun onCleared() {
        // Cleared mid-session (e.g. admin release): do not leave the guardian token valid on the server.
        val t = token
        val st = stationToken()
        token = null
        idle.disarm()
        if (t != null) guardian().logout(st, t)
    }

    private fun startStep(): GuardianStep.Start? = _step.value as? GuardianStep.Start

    fun onPhone(s: String) {
        val cur = startStep() ?: return
        if (cur.submitting) return
        idle.arm()
        _step.value = cur.copy(phone = s.filter { it.isDigit() }.take(20), error = null)
    }

    fun onBabyId(s: String) {
        val cur = startStep() ?: return
        if (cur.submitting) return
        idle.arm()
        _step.value = cur.copy(babyId = s.take(40), error = null)
    }

    fun login() {
        val cur = startStep() ?: return
        if (cur.submitting) return
        val phone = cur.phone.trim()
        val babyId = cur.babyId.trim()
        val st = stationToken()
        if (phone.isEmpty() || babyId.isEmpty()) return
        _step.value = cur.copy(error = null, submitting = true)
        val g = gen
        viewModelScope.launch {
            try {
                val r = guardian().login(st, phone, babyId)
                if (g != gen) return@launch
                when (r) {
                    is ApiResult.Ok -> {
                        val babies = r.value.babies
                        if (babies.isEmpty()) {
                            guardian().logout(st, r.value.token)
                            _step.value = cur.copy(error = unexpected, submitting = false)
                            return@launch
                        }
                        token = r.value.token
                        lastRequest = clock.now()
                        idle.arm()
                        _step.value =
                            if (babies.size == 1) GuardianStep.Stock(babies[0]) else GuardianStep.SelectBaby(babies)
                    }
                    is ApiResult.Err ->
                        if (r.error.isStationInvalid()) {
                            reset()
                            onStationInvalid()
                        } else {
                            _step.value = cur.copy(error = r.error.message, submitting = false)
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                if (g == gen) _step.value = cur.copy(error = unexpected, submitting = false)
            }
        }
    }

    fun selectBaby(b: BabyDto) {
        val cur = _step.value as? GuardianStep.SelectBaby ?: return
        if (b in cur.babies) _step.value = GuardianStep.Stock(b)
    }

    fun startSubmit() {
        val cur = _step.value as? GuardianStep.Stock ?: return
        _step.value = GuardianStep.PickCount(cur.baby, null)
        keepAliveInBackground()
    }

    fun pickCount(n: Int) {
        val cur = _step.value as? GuardianStep.PickCount ?: return
        if (n !in 1..GUARDIAN_MAX_PACKS) return
        _step.value = GuardianStep.ConfirmSubmit(cur.baby, n)
        keepAliveInBackground()
    }

    private sealed interface Alive {
        data object Ok : Alive
        data object Ended : Alive
        data class Failed(val message: String) : Alive
    }

    private fun stale() = !Duration.between(lastRequest, clock.now()).minus(KEEPALIVE_AFTER).isNegative

    /** Steps between login and submit send no requests: keep the server session alive while the parent is active. */
    private fun keepAliveInBackground() {
        val st = stationToken()
        val t = token ?: return
        if (!stale()) return
        val g = gen
        viewModelScope.launch { keepAlive(st, t, g) }
    }

    /** Asks the server for the baby list when the last request is stale. Session-over answers end the flow here. */
    private suspend fun keepAlive(st: String?, t: String, g: Int): Alive = keepAliveLock.withLock {
        if (g != gen) return@withLock Alive.Ended
        if (!stale()) return@withLock Alive.Ok
        try {
            when (val r = guardian().babies(st, t)) {
                is ApiResult.Ok -> {
                    if (g != gen) Alive.Ended else { lastRequest = clock.now(); Alive.Ok }
                }
                is ApiResult.Err ->
                    if (g != gen || endIfSessionOver(r.error)) Alive.Ended
                    else Alive.Failed(if (r.error.status == null) unexpected else r.error.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            if (g != gen) Alive.Ended else Alive.Failed(unexpected)
        }
    }

    /** One step back: confirmation to the number pad, number pad to the stock screen. Ignored while sending. */
    fun back() {
        if (_submitting.value) return
        when (val cur = _step.value) {
            is GuardianStep.ConfirmSubmit -> _step.value = GuardianStep.PickCount(cur.baby, cur.count)
            is GuardianStep.PickCount -> _step.value = GuardianStep.Stock(cur.baby)
            else -> {}
        }
    }

    fun submit() {
        val cur = _step.value as? GuardianStep.ConfirmSubmit ?: return
        val st = stationToken()
        val t = token ?: return
        // Set before launching so a second tap can never send a second submission.
        if (_submitting.value) return
        _submitting.value = true
        val g = gen
        viewModelScope.launch {
            try {
                // A stale server session would answer 401: ping first, and send only once it is known to be alive.
                when (val a = keepAlive(st, t, g)) {
                    Alive.Ended -> return@launch
                    is Alive.Failed -> {
                        if (g == gen) _step.value = GuardianStep.Stock(cur.baby, a.message) // nothing was sent
                        return@launch
                    }
                    Alive.Ok -> {}
                }
                when (val r = guardian().submit(st, t, cur.baby.patientId, cur.count)) {
                    is ApiResult.Ok -> if (g == gen) {
                        lastRequest = clock.now()
                        _step.value = GuardianStep.Done(cur.baby, cur.count, r.value.balance, r.value.pendingCount)
                    }
                    is ApiResult.Err -> if (g == gen && !endIfSessionOver(r.error)) {
                        // status null: the answer was lost, so whether it was saved is unknown.
                        val notice = if (r.error.status == null) submitUncertain else r.error.message
                        refreshToStock(st, t, cur.baby, g, notice)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                if (g == gen) refreshToStock(st, t, cur.baby, g, submitUncertain)
            } finally {
                if (g == gen) _submitting.value = false
            }
        }
    }

    /** station_invalid or another 401: the guardian session is over. Returns true when handled. */
    private fun endIfSessionOver(e: ApiError): Boolean {
        if (e.isStationInvalid()) {
            reset()
            onStationInvalid()
            return true
        }
        if (e.status == 401) {
            reset()
            _step.value = blank.copy(error = sessionExpired) // cleared as soon as the parent types
            return true
        }
        return false
    }

    private suspend fun refreshToStock(st: String?, t: String, baby: BabyDto, g: Int, notice: String) {
        val fallback = GuardianStep.Stock(baby, notice)
        try {
            when (val r = guardian().babies(st, t)) {
                is ApiResult.Ok -> if (g == gen) {
                    lastRequest = clock.now()
                    val fresh = r.value.firstOrNull { it.patientId == baby.patientId }
                    _step.value = if (fresh != null) GuardianStep.Stock(fresh, notice) else fallback
                }
                is ApiResult.Err -> if (g == gen && !endIfSessionOver(r.error)) _step.value = fallback
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            if (g == gen) _step.value = fallback
        }
    }
}
