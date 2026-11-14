package app.milkbank.ui.patients

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** kind is "intake", "use", "discard" or "intake_confirm" (a nurse confirmed a guardian submission). */
/** [balanceMl] and [unknown] describe the stock after the change: known mL and packs without a volume. */
data class ResultBanner(
    val regNo: String, val kind: String, val count: Int, val balance: Int,
    val balanceMl: Int = 0, val unknown: Int = 0,
)

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

    /** A cancel changed the stock: keep the banner truthful for that baby. */
    fun updateBalance(regNo: String, balance: Int, balanceMl: Int = 0, unknown: Int = 0) = _last.update { b ->
        if (b != null && b.regNo == regNo) b.copy(balance = balance, balanceMl = balanceMl, unknown = unknown) else b
    }

    fun notifyChanged() = _changes.update { it + 1 }

    fun clear() {
        _last.value = null
    }
}
