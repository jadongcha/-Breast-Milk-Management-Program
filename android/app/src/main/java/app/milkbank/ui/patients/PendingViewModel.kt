package app.milkbank.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.PendingDto
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.data.SubmissionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant

const val REJECT_NOT_RECEIVED = "not_received"
const val REJECT_OTHER = "other"
private const val MIN_COUNT = 1
private const val MAX_COUNT = 100

sealed interface PendingDialog {
    data class Confirm(val item: PendingDto, val count: Int, val submitting: Boolean = false) : PendingDialog
    data class Reject(
        val item: PendingDto,
        val reason: String? = null,
        val memo: String = "",
        val submitting: Boolean = false,
    ) : PendingDialog

    /** [before] is the stock before this intake (balance - received). */
    data class Done(val item: PendingDto, val received: Int, val balance: Int, val before: Int) : PendingDialog
}

/** Nurse home: guardian submissions waiting for a nurse to confirm the received pack count, or reject. */
@Suppress("unused")
class PendingViewModel(
    private val submissions: () -> SubmissionRepository,
    private val banner: ResultBannerStore,
    private val clock: Clock,
    private val kindLabel: String,
    private val uncertain: String,
    private val unexpected: String,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _items = MutableStateFlow<List<PendingDto>>(emptyList())
    val items: StateFlow<List<PendingDto>> = _items.asStateFlow()
    private val _dialog = MutableStateFlow<PendingDialog?>(null)
    val dialog: StateFlow<PendingDialog?> = _dialog.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var refreshJob: Job? = null

    // A refresh failure message goes away when a later refresh succeeds; an action message (409, uncertain)
    // must survive the refresh that follows it, so the nurse actually sees it.
    private var errorFromRefresh = false

    init {
        // Stock changed elsewhere (or by our own confirm): reload. The first value is the initial one.
        viewModelScope.launch { banner.changes.drop(1).collect { refresh() } }
    }

    fun now(): Instant = clock.now()

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                when (val r = submissions().pending()) {
                    is ApiResult.Ok -> {
                        _items.value = r.value
                        if (errorFromRefresh) _error.value = null
                    }
                    is ApiResult.Err -> setError(r.error.message, fromRefresh = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                setError(unexpected, fromRefresh = true)
            }
        }
    }

    private fun setError(message: String?, fromRefresh: Boolean = false) {
        _error.value = message
        errorFromRefresh = fromRefresh && message != null
    }

    fun clearError() = setError(null)

    /** A discharged baby's pack count cannot be confirmed into stock: only rejecting is possible. */
    fun openConfirm(item: PendingDto) {
        if (item.discharged || isBusy()) return
        setError(null)
        _dialog.value = PendingDialog.Confirm(item, item.count.coerceIn(MIN_COUNT, MAX_COUNT))
    }

    fun inc() = adjust(+1)
    fun dec() = adjust(-1)

    private fun adjust(delta: Int) {
        val d = _dialog.value as? PendingDialog.Confirm ?: return
        if (d.submitting) return
        _dialog.value = d.copy(count = (d.count + delta).coerceIn(MIN_COUNT, MAX_COUNT))
    }

    fun confirm() {
        val d = _dialog.value as? PendingDialog.Confirm ?: return
        // Set synchronously (before launching) so a second tap can never start a second intake.
        if (d.submitting) return
        _dialog.value = d.copy(submitting = true)
        viewModelScope.launch {
            try {
                when (val r = submissions().confirm(d.item.id, d.count)) {
                    is ApiResult.Ok -> {
                        val balance = r.value.balance
                        banner.post(ResultBanner(d.item.babyId, "intake_confirm", d.count, balance))
                        _dialog.value = PendingDialog.Done(d.item, d.count, balance, balance - d.count)
                        refresh()
                    }
                    is ApiResult.Err -> failed(r.error.status, r.error.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                setError(unexpected)
                _dialog.value = null
                refresh()
            }
        }
    }

    fun openReject(item: PendingDto) {
        if (isBusy()) return
        setError(null)
        _dialog.value = PendingDialog.Reject(item)
    }

    fun setReason(reason: String) {
        val d = _dialog.value as? PendingDialog.Reject ?: return
        if (d.submitting || (reason != REJECT_NOT_RECEIVED && reason != REJECT_OTHER)) return
        _dialog.value = d.copy(reason = reason)
    }

    fun setMemo(memo: String) {
        val d = _dialog.value as? PendingDialog.Reject ?: return
        if (!d.submitting) _dialog.value = d.copy(memo = memo)
    }

    fun canReject(d: PendingDialog.Reject): Boolean =
        !d.submitting && (d.reason == REJECT_NOT_RECEIVED || (d.reason == REJECT_OTHER && d.memo.isNotBlank()))

    fun reject() {
        val d = _dialog.value as? PendingDialog.Reject ?: return
        if (!canReject(d)) return
        val reason = d.reason ?: return
        _dialog.value = d.copy(submitting = true)
        viewModelScope.launch {
            try {
                val memo = if (reason == REJECT_OTHER) d.memo.trim() else null
                when (val r = submissions().reject(d.item.id, reason, memo)) {
                    is ApiResult.Ok -> {
                        _dialog.value = null
                        refresh()
                    }
                    is ApiResult.Err -> failed(r.error.status, r.error.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                setError(unexpected)
                _dialog.value = null
                refresh()
            }
        }
    }

    /**
     * An HTTP status means the server refused (e.g. 409 already handled): show its message. No status
     * (timeout, lost connection) may mean the server did save it: warn. Either way the table is reloaded.
     */
    private fun failed(status: Int?, message: String) {
        setError(if (status != null) message else uncertain)
        _dialog.value = null
        refresh()
    }

    /** Closes the dialog. Ignored while a request is in flight. */
    fun close() {
        if (!isBusy()) _dialog.value = null
    }

    private fun isBusy(): Boolean = when (val d = _dialog.value) {
        is PendingDialog.Confirm -> d.submitting
        is PendingDialog.Reject -> d.submitting
        else -> false
    }
}
