package app.milkbank.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.PatientDetailDto
import app.milkbank.api.RecordDto
import app.milkbank.api.RecordResultDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.data.PatientRepository
import app.milkbank.data.StockRepository
import app.milkbank.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** kind is "intake", "use" or "discard"; reason is required for "discard". */
data class PendingAction(val kind: String, val count: Int, val reason: String?, val memo: String?)

val DISCARD_REASONS = listOf("thawed_leftover", "contaminated", "other")
private val STOCK_KINDS = setOf("intake", "use", "discard")
const val MAX_PACKS = 10

class PatientDetailViewModel(
    private val patientId: Int,
    private val patientRepo: () -> PatientRepository,
    private val stockRepo: () -> StockRepository,
    private val banner: ResultBannerStore,
    private val session: SessionManager,
    private val clock: Clock,
    private val savedMessage: String = "",
    private val unexpectedError: String = "",
    private val saveUncertainMessage: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _detail = MutableStateFlow<PatientDetailDto?>(null)
    val detail: StateFlow<PatientDetailDto?> = _detail.asStateFlow()
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()
    private val _pendingCancel = MutableStateFlow<RecordDto?>(null)
    val pendingCancel: StateFlow<RecordDto?> = _pendingCancel.asStateFlow()
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var refreshJob: Job? = null

    // Bumped by every applied write; a refresh that began before a write must not overwrite it.
    private var writeSeq = 0

    fun refresh() {
        if (_submitting.value) return
        refreshJob?.cancel()
        val startedAt = writeSeq
        refreshJob = viewModelScope.launch {
            try {
                when (val r = patientRepo().detail(patientId)) {
                    is ApiResult.Ok -> if (startedAt == writeSeq) {
                        _detail.value = r.value
                        _error.value = null
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            }
        }
    }

    fun canCancel(record: RecordDto): Boolean {
        val me = session.state.value?.staff ?: return false
        return canCancel(record, me, clock.now())
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /** Opens the confirmation. Ignored while a save is in flight or when the input is invalid. */
    fun request(kind: String, count: Int, reason: String?, memo: String?) {
        if (_submitting.value) return
        if (kind !in STOCK_KINDS || count !in 1..MAX_PACKS) return
        if (kind == "discard" && reason !in DISCARD_REASONS) return
        _error.value = null
        _pending.value = PendingAction(
            kind, count,
            if (kind == "discard") reason else null,
            memo?.trim()?.ifEmpty { null },
        )
    }

    fun dismissPending() {
        if (!_submitting.value) _pending.value = null
    }

    fun confirm() {
        val p = _pending.value ?: return
        // Set synchronously (before launching) so a second tap can never start a second save.
        if (!_submitting.compareAndSet(false, true)) return
        val regNo = _detail.value?.regNo
        viewModelScope.launch {
            try {
                when (val r = stockRepo().add(patientId, p.kind, p.count, p.reason, p.memo)) {
                    is ApiResult.Ok -> {
                        applyResult(r.value)
                        _message.value = savedMessage
                        if (regNo != null) banner.post(ResultBanner(regNo, p.kind, p.count, r.value.balance))
                        else banner.notifyChanged()
                    }
                    // 401: the interceptor already ended the session and MainActivity shows the login screen.
                    is ApiResult.Err -> onWriteFailed(r.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _pending.value = null
                _submitting.value = false
            }
        }
    }

    /** Opens the cancel confirmation for [record]; the record is kept so the dialog can identify it. */
    fun askCancel(record: RecordDto) {
        if (!_submitting.value) _pendingCancel.value = record
    }

    fun dismissCancel() {
        if (!_submitting.value) _pendingCancel.value = null
    }

    fun cancelRecord(id: Int) {
        if (!_submitting.compareAndSet(false, true)) return
        val regNo = _detail.value?.regNo
        viewModelScope.launch {
            try {
                when (val r = stockRepo().cancel(id)) {
                    is ApiResult.Ok -> {
                        applyResult(r.value)
                        _message.value = savedMessage
                        regNo?.let { banner.updateBalance(it, r.value.balance) }
                        banner.notifyChanged()
                    }
                    is ApiResult.Err -> onWriteFailed(r.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _pendingCancel.value = null
                _submitting.value = false
            }
        }
    }

    /**
     * A failure without an HTTP status (timeout, lost connection) may have happened after the server saved
     * the record: show the server's real state and say that saving is uncertain.
     */
    private suspend fun onWriteFailed(error: ApiError) {
        if (error.status != null) {
            _error.value = error.message
            return
        }
        _error.value = "${error.message}\n$saveUncertainMessage"
        refreshJob?.cancel()
        writeSeq++ // an older in-flight refresh must not overwrite the one below
        val startedAt = writeSeq
        try {
            val r = patientRepo().detail(patientId)
            if (r is ApiResult.Ok && startedAt == writeSeq) _detail.value = r.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun applyResult(result: RecordResultDto) {
        writeSeq++
        refreshJob?.cancel()
        val rec = result.record
        _detail.update { d ->
            d?.copy(
                balance = result.balance,
                records = listOf(rec) + d.records.map {
                    if (rec.targetId != null && it.id == rec.targetId) it.copy(canceled = true) else it
                },
            )
        }
    }
}
