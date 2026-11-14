package app.milkbank.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.SettingsDto
import app.milkbank.api.SettingsPatch
import app.milkbank.core.ApiResult
import app.milkbank.data.AdminRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingKey { DAYS_THRESHOLD, PACKS_THRESHOLD, AVG_DAYS, STAFF_IDLE_MINUTES, RETENTION_DAYS }

private fun SettingsDto.toFields(): Map<SettingKey, String> = mapOf(
    SettingKey.DAYS_THRESHOLD to daysThreshold.toString(),
    SettingKey.PACKS_THRESHOLD to packsThreshold.toString(),
    SettingKey.AVG_DAYS to avgDays.toString(),
    SettingKey.STAFF_IDLE_MINUTES to staffIdleMinutes.toString(),
    SettingKey.RETENTION_DAYS to retentionDays.toString(),
)

/**
 * Admin settings: five integer fields held as text. Only changed fields are sent; range errors come back
 * from the server and are shown as-is. Lowering the retention period asks for confirmation first.
 */
class SettingsViewModel(
    private val admin: () -> AdminRepository,
    private val onIdleMinutesSaved: (Int) -> Unit = {},
    private val unexpectedError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private var loaded: SettingsDto? = null

    private val _fields = MutableStateFlow<Map<SettingKey, String>>(emptyMap())
    val fields: StateFlow<Map<SettingKey, String>> = _fields.asStateFlow()
    private val _invalid = MutableStateFlow<Set<SettingKey>>(emptySet())
    /** Fields whose text is not a whole number; saving is blocked while any exists. */
    val invalid: StateFlow<Set<SettingKey>> = _invalid.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _confirmRetention = MutableStateFlow(false)
    val confirmRetention: StateFlow<Boolean> = _confirmRetention.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                when (val r = admin().settings()) {
                    is ApiResult.Ok -> {
                        applyLoaded(r.value)
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

    private fun applyLoaded(s: SettingsDto) {
        loaded = s
        _fields.value = s.toFields()
        _invalid.value = emptySet()
    }

    fun setField(key: SettingKey, text: String) {
        _fields.update { it + (key to text) }
        _invalid.update { if (text.toIntOrNull() == null) it + key else it - key }
        _saved.value = false
        _error.value = null
    }

    /** The changed fields as a patch, or null when nothing changed (or nothing is loaded). */
    private fun changes(): SettingsPatch? {
        val base = loaded ?: return null
        val f = _fields.value
        fun changed(key: SettingKey, old: Int): Int? = f[key]?.toIntOrNull()?.takeIf { it != old }
        val patch = SettingsPatch(
            daysThreshold = changed(SettingKey.DAYS_THRESHOLD, base.daysThreshold),
            packsThreshold = changed(SettingKey.PACKS_THRESHOLD, base.packsThreshold),
            avgDays = changed(SettingKey.AVG_DAYS, base.avgDays),
            staffIdleMinutes = changed(SettingKey.STAFF_IDLE_MINUTES, base.staffIdleMinutes),
            retentionDays = changed(SettingKey.RETENTION_DAYS, base.retentionDays),
        )
        return patch.takeIf { it != SettingsPatch() }
    }

    fun save() {
        if (_saving.value || _invalid.value.isNotEmpty()) return
        val patch = changes() ?: return
        val base = loaded ?: return
        if (patch.retentionDays != null && patch.retentionDays < base.retentionDays) {
            _confirmRetention.value = true
            return
        }
        send(patch)
    }

    fun confirmRetentionSave() {
        if (!_confirmRetention.value) return
        _confirmRetention.value = false
        if (_saving.value || _invalid.value.isNotEmpty()) return
        changes()?.let(::send)
    }

    fun dismissRetentionConfirm() {
        _confirmRetention.value = false
    }

    private fun send(patch: SettingsPatch) {
        if (!_saving.compareAndSet(false, true)) return
        _error.value = null
        viewModelScope.launch {
            try {
                when (val r = admin().saveSettings(patch)) {
                    is ApiResult.Ok -> {
                        applyLoaded(r.value)
                        _saved.value = true
                        if (patch.staffIdleMinutes != null) onIdleMinutesSaved(r.value.staffIdleMinutes)
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _saving.value = false
            }
        }
    }
}
