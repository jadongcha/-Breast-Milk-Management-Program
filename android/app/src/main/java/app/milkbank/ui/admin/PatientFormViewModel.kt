package app.milkbank.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.PatientDetailDto
import app.milkbank.api.PatientForm
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.data.AdminRepository
import app.milkbank.data.PatientRepository
import app.milkbank.ui.patients.DISCARD_REASONS
import app.milkbank.ui.patients.ResultBanner
import app.milkbank.ui.patients.ResultBannerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** [admittedOn] is "YYYY-MM-DD" (KST). */
data class PatientFormState(
    val regNo: String = "",
    val babyName: String = "",
    val bed: String = "",
    val guardianName: String = "",
    val guardianPhone: String = "",
    val admittedOn: String = "",
)

/**
 * Register ([patientId] null) or edit a patient. Empty-field checks are left to the server, whose
 * messages are shown verbatim. On edit the alert settings come from the loaded detail and are sent back unchanged.
 */
class PatientFormViewModel(
    private val patientId: Int?,
    private val repo: () -> PatientRepository,
    private val admin: () -> AdminRepository,
    private val banner: ResultBannerStore,
    clock: Clock,
    private val unexpectedError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _form = MutableStateFlow(
        PatientFormState(admittedOn = clock.now().atZone(SEOUL).toLocalDate().toString()),
    )
    val form: StateFlow<PatientFormState> = _form.asStateFlow()
    private val _loading = MutableStateFlow(patientId != null)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _dischargeDialog = MutableStateFlow(false)
    val dischargeDialog: StateFlow<Boolean> = _dischargeDialog.asStateFlow()
    private val _eraseDialog = MutableStateFlow(false)
    val eraseDialog: StateFlow<Boolean> = _eraseDialog.asStateFlow()

    /** The personal data was erased: every field is read-only and no action is offered. */
    private val _erased = MutableStateFlow(false)
    val erased: StateFlow<Boolean> = _erased.asStateFlow()
    private val _canDischarge = MutableStateFlow(false)
    val canDischarge: StateFlow<Boolean> = _canDischarge.asStateFlow()
    private val _canErase = MutableStateFlow(false)
    val canErase: StateFlow<Boolean> = _canErase.asStateFlow()

    /** Packs still in stock; shown in the discharge dialog. */
    private val _balance = MutableStateFlow(0)
    val balance: StateFlow<Int> = _balance.asStateFlow()

    private var existing: PatientDetailDto? = null

    init {
        if (patientId != null) load(patientId)
    }

    private fun load(id: Int) {
        viewModelScope.launch {
            try {
                when (val r = repo().detail(id)) {
                    is ApiResult.Ok -> {
                        existing = r.value
                        refreshActions()
                        _balance.value = r.value.balance
                        _form.value = PatientFormState(
                            r.value.regNo, r.value.babyName, r.value.bed,
                            r.value.guardianName, r.value.guardianPhone, r.value.admittedOn,
                        )
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _loading.value = false
            }
        }
    }

    private fun refreshActions() {
        val d = existing ?: return
        val erased = d.erasedAt != null
        val discharged = d.status != "admitted"
        _erased.value = erased
        _canDischarge.value = !discharged && !erased
        _canErase.value = discharged && !erased
    }

    fun openDischarge() { if (_canDischarge.value) _dischargeDialog.value = true }
    fun closeDischarge() { _dischargeDialog.value = false }
    fun openErase() { if (_canErase.value) _eraseDialog.value = true }
    fun closeErase() { _eraseDialog.value = false }

    /** [remaining] is "return" or "discard"; a discard needs one of the known reasons. */
    fun discharge(remaining: String, discardReason: String?) {
        val id = patientId ?: return
        val base = existing ?: return
        if (!_canDischarge.value) return
        if (remaining != "return" && remaining != "discard") return
        if (remaining == "discard" && discardReason !in DISCARD_REASONS) return
        act {
            val reason = if (remaining == "discard") discardReason else null
            when (val r = admin().discharge(id, remaining, reason)) {
                is ApiResult.Ok -> {
                    val kind = if (remaining == "discard") "discard" else "discharge_return"
                    banner.post(ResultBanner(base.regNo, kind, base.balance, r.value.balance))
                    _saved.value = true
                }
                is ApiResult.Err -> _error.value = r.error.message
            }
        }
    }

    fun erase() {
        val id = patientId ?: return
        if (!_canErase.value) return
        act {
            when (val r = admin().erase(id)) {
                is ApiResult.Ok -> {
                    _erased.value = true
                    banner.notifyChanged()
                    _saved.value = true
                }
                is ApiResult.Err -> _error.value = r.error.message
            }
        }
    }

    /** Runs a server action behind the same one-at-a-time lock as saving; closes both dialogs when done. */
    private fun act(block: suspend () -> Unit) {
        if (!_submitting.compareAndSet(false, true)) return
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _submitting.value = false
                _dischargeDialog.value = false
                _eraseDialog.value = false
            }
        }
    }

    fun setRegNo(v: String) = _form.update { it.copy(regNo = v) }
    fun setBabyName(v: String) = _form.update { it.copy(babyName = v) }
    fun setBed(v: String) = _form.update { it.copy(bed = v) }
    fun setGuardianName(v: String) = _form.update { it.copy(guardianName = v) }
    fun setGuardianPhone(v: String) = _form.update { it.copy(guardianPhone = v) }
    fun setAdmittedOn(v: String) = _form.update { it.copy(admittedOn = v) }

    fun submit() {
        val base = existing
        if (_erased.value) return // erased patients cannot be edited
        if (patientId != null && base == null) return // edit not loaded yet: thresholds unknown
        // Set synchronously (before launching) so a second tap can never start a second save.
        if (!_submitting.compareAndSet(false, true)) return
        _error.value = null
        val f = _form.value
        val body = PatientForm(
            regNo = f.regNo, babyName = f.babyName, bed = f.bed,
            guardianName = f.guardianName, guardianPhone = f.guardianPhone,
            admittedOn = f.admittedOn.ifBlank { null },
            alertEnabled = base?.alertEnabled ?: true,
            daysThreshold = base?.daysThreshold,
            packsThreshold = base?.packsThreshold,
        )
        viewModelScope.launch {
            try {
                val r = if (patientId == null) repo().create(body) else repo().update(patientId, body)
                when (r) {
                    is ApiResult.Ok -> {
                        banner.notifyChanged()
                        _saved.value = true
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _submitting.value = false
            }
        }
    }
}
