package app.milkbank.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.PackDto
import app.milkbank.api.PatientDetailDto
import app.milkbank.api.RecordDto
import app.milkbank.api.RecordResultDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.data.PatientRepository
import app.milkbank.data.StockRepository
import app.milkbank.session.SessionManager
import app.milkbank.ui.components.PackMlsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * kind is "intake", "use" or "discard"; reason is required for "discard".
 * [packMls] (intake) and [packIds] (use/discard, null = the oldest packs) go to the server as they are;
 * [goingPacks] is what the confirmation shows for use/discard.
 */
data class PendingAction(
    val kind: String, val count: Int, val reason: String?, val memo: String?,
    val packMls: List<Int?>? = null, val packIds: List<Int>? = null,
    val goingPacks: List<PackDto> = emptyList(),
)

/**
 * The open intake/use/discard window. [count] is null until picked (intake, discard).
 * [previewPacks] are the packs that would leave (use/discard): the chosen ones, else the oldest [count].
 * [picking] is the ticked list while "팩 고르기" is open, null otherwise; [chosenPackIds] is the finished choice.
 */
data class StockDialogState(
    val kind: String,
    val count: Int?,
    val packMls: PackMlsState = PackMlsState.empty(0),
    val reason: String? = null,
    val memo: String = "",
    val previewPacks: List<PackDto> = emptyList(),
    val chosenPackIds: List<Int>? = null,
    val picking: List<Int>? = null,
) {
    val choosing: Boolean get() = picking != null

    val canSubmit: Boolean
        get() = when {
            choosing || count == null -> false
            kind == "discard" && reason !in DISCARD_REASONS -> false
            kind == "intake" -> packMls.values != null && !packMls.allInvalid
            else -> true
        }
}

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
    private val _dialog = MutableStateFlow<StockDialogState?>(null)
    val dialog: StateFlow<StockDialogState?> = _dialog.asStateFlow()
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
                        setDetail(r.value)
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
        if (_detail.value?.let { it.status != "admitted" } == true) return false // the server rejects it too
        val me = session.state.value?.staff ?: return false
        return canCancel(record, me, clock.now())
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }

    private fun setDetail(d: PatientDetailDto?) {
        _detail.value = d
        syncDialog()
    }

    // ---- intake / use / discard window ----

    /** Opens the window for [kind]. "use" starts at one pack; the others wait for a pick. */
    fun openDialog(kind: String) {
        if (_submitting.value || kind !in STOCK_KINDS) return
        updateDialog(StockDialogState(kind, if (kind == "use") 1 else null))
    }

    fun closeDialog() {
        _dialog.value = null
    }

    fun setCount(n: Int) = editDialog { s ->
        if (n !in 1..MAX_PACKS || s.choosing) s
        else s.copy(count = n, packMls = if (s.kind == "intake") s.packMls.resize(n) else s.packMls, chosenPackIds = null)
    }

    fun setPackMls(state: PackMlsState) = editDialog { s ->
        if (s.kind == "intake" && state.texts.size == s.packMls.texts.size) s.copy(packMls = state) else s
    }

    fun setReason(reason: String) = editDialog { it.copy(reason = reason) }

    fun setMemo(memo: String) = editDialog { it.copy(memo = memo) }

    /** Opens the pack list to tick exactly [StockDialogState.count] packs (use/discard only). */
    fun openChoosePacks() = editDialog { s ->
        val count = s.count
        if (s.kind == "intake" || count == null || (_detail.value?.packs?.size ?: 0) < count) s
        else s.copy(picking = s.chosenPackIds ?: emptyList())
    }

    fun togglePack(id: Int) = editDialog { s ->
        val picking = s.picking
        val count = s.count
        if (picking == null || count == null || _detail.value?.packs?.any { it.id == id } != true) s
        else if (id in picking) s.copy(picking = picking - id)
        else if (picking.size < count) s.copy(picking = picking + id)
        else s
    }

    /** Accepts the ticked packs only when there are exactly as many as the pack count. */
    fun finishChoosePacks() = editDialog { s ->
        val picking = s.picking
        if (picking == null || picking.size != s.count) s
        else {
            val order = _detail.value?.packs.orEmpty().map { it.id }
            s.copy(chosenPackIds = picking.sortedBy { order.indexOf(it) }, picking = null)
        }
    }

    /** Closes the window and opens the confirmation, unless the input is not complete. */
    fun submitDialog() {
        val s = _dialog.value ?: return
        val count = s.count ?: return
        if (_submitting.value || !s.canSubmit) return
        request(
            s.kind, count, s.reason, s.memo,
            packMls = if (s.kind == "intake") s.packMls.values else null,
            packIds = s.chosenPackIds,
        )
        if (_pending.value != null) _dialog.value = null
    }

    private fun editDialog(change: (StockDialogState) -> StockDialogState) {
        val s = _dialog.value ?: return
        updateDialog(change(s))
    }

    private fun updateDialog(s: StockDialogState) {
        _dialog.value = withPreview(s)
    }

    /** The pack list changed under an open window: refresh the preview and drop choices that no longer exist. */
    private fun syncDialog() {
        val s = _dialog.value ?: return
        val ids = _detail.value?.packs.orEmpty().map { it.id }.toSet()
        val chosen = s.chosenPackIds?.takeIf { c -> c.all { it in ids } }
        updateDialog(s.copy(chosenPackIds = chosen, picking = s.picking?.filter { it in ids }))
    }

    private fun withPreview(s: StockDialogState): StockDialogState =
        if (s.kind == "intake") s else s.copy(previewPacks = packsThatLeave(s.count ?: 0, s.chosenPackIds))

    private fun packsThatLeave(count: Int, ids: List<Int>?): List<PackDto> {
        val packs = _detail.value?.packs.orEmpty()
        return if (ids != null) packs.filter { it.id in ids } else packs.take(count)
    }

    /** Opens the confirmation. Ignored while a save is in flight or when the input is invalid. */
    fun request(
        kind: String, count: Int, reason: String?, memo: String?,
        packMls: List<Int?>? = null, packIds: List<Int>? = null,
    ) {
        if (_submitting.value) return
        if (kind !in STOCK_KINDS || count !in 1..MAX_PACKS) return
        if (kind == "discard" && reason !in DISCARD_REASONS) return
        if (packMls != null && (kind != "intake" || packMls.size != count)) return
        if (packIds != null && (kind == "intake" || packIds.size != count)) return
        _error.value = null
        _pending.value = PendingAction(
            kind, count,
            if (kind == "discard") reason else null,
            memo?.trim()?.ifEmpty { null },
            packMls, packIds,
            if (kind == "intake") emptyList() else packsThatLeave(count, packIds),
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
                when (val r = stockRepo().add(patientId, p.kind, p.count, p.reason, p.memo, p.packMls, p.packIds)) {
                    is ApiResult.Ok -> {
                        applyResult(r.value)
                        _message.value = savedMessage
                        if (regNo != null) {
                            banner.post(ResultBanner(regNo, p.kind, p.count, r.value.balance, r.value.balanceMl, r.value.unknownMlPacks))
                        } else banner.notifyChanged()
                        reloadPacks()
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
                        regNo?.let { banner.updateBalance(it, r.value.balance, r.value.balanceMl, r.value.unknownMlPacks) }
                        banner.notifyChanged()
                        reloadPacks()
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
            if (r is ApiResult.Ok && startedAt == writeSeq) setDetail(r.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    /**
     * A write changes which packs are left but its answer does not list them: fetch the list again.
     * Only the pack list is taken from it, so the balance and records from the write answer stay as they are.
     */
    private fun reloadPacks() {
        viewModelScope.launch {
            try {
                val r = patientRepo().detail(patientId)
                if (r is ApiResult.Ok) {
                    _detail.update { it?.copy(packs = r.value.packs) }
                    syncDialog()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private fun applyResult(result: RecordResultDto) {
        writeSeq++
        refreshJob?.cancel()
        val rec = result.record
        _detail.update { d ->
            d?.copy(
                balance = result.balance,
                balanceMl = result.balanceMl,
                unknownMlPacks = result.unknownMlPacks,
                records = listOf(rec) + d.records.map {
                    if (rec.targetId != null && it.id == rec.targetId) it.copy(canceled = true) else it
                },
            )
        }
    }
}
