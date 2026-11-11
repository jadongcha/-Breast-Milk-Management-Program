package app.milkbank.ui.patients

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** kind is "intake", "use" or "discard". */
data class ResultBanner(val regNo: String, val kind: String, val count: Int, val balance: Int)

/** Shared by detail (writer) and home (reader); cleared when the session ends. */
class ResultBannerStore {
    private val _last = MutableStateFlow<ResultBanner?>(null)
    val last: StateFlow<ResultBanner?> = _last.asStateFlow()

    private val _changes = MutableStateFlow(0L)

    /** Bumps whenever stock changed, so list screens reload. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun post(banner: ResultBanner) {
        _last.value = banner
        notifyChanged()
    }

    fun notifyChanged() = _changes.update { it + 1 }

    fun clear() {
        _last.value = null
    }
}
