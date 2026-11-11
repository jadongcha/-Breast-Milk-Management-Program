package app.milkbank.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.BabyDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.isStationInvalid
import app.milkbank.data.GuardianRepository
import app.milkbank.station.GuardianIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

const val GUARDIAN_MAX_PACKS = 10

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
    private val stationToken: () -> String?,
    private val idle: GuardianIdle,
    private val onStationInvalid: () -> Unit,
    private val submitUncertain: String,
    private val unexpected: String,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val blank = GuardianStep.Start("", "", null, false)
    private val _step = MutableStateFlow<GuardianStep>(blank)
    val step: StateFlow<GuardianStep> = _step.asStateFlow()
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private var token: String? = null

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
        if (t != null && st != null) guardian().logout(st, t)
    }

    fun finish() = reset()

    private fun startStep(): GuardianStep.Start? = _step.value as? GuardianStep.Start

    fun onPhone(s: String) {
        val cur = startStep() ?: return
        if (cur.submitting) return
        idle.arm()
        _step.value = cur.copy(phone = s.filter { it.isDigit() || it == '-' || it == '+' }.take(20), error = null)
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
        val st = stationToken() ?: return
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
                            _step.value = cur.copy(error = unexpected, submitting = false)
                            return@launch
                        }
                        token = r.value.token
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
    }

    fun pickCount(n: Int) {
        val cur = _step.value as? GuardianStep.PickCount ?: return
        if (n !in 1..GUARDIAN_MAX_PACKS) return
        _step.value = GuardianStep.ConfirmSubmit(cur.baby, n)
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
        val st = stationToken() ?: return
        val t = token ?: return
        // Set before launching so a second tap can never send a second submission.
        if (_submitting.value) return
        _submitting.value = true
        val g = gen
        viewModelScope.launch {
            try {
                when (val r = guardian().submit(st, t, cur.baby.patientId, cur.count)) {
                    is ApiResult.Ok -> if (g == gen) {
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
            return true
        }
        return false
    }

    private suspend fun refreshToStock(st: String, t: String, baby: BabyDto, g: Int, notice: String) {
        val fallback = GuardianStep.Stock(baby, notice)
        try {
            when (val r = guardian().babies(st, t)) {
                is ApiResult.Ok -> if (g == gen) {
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
