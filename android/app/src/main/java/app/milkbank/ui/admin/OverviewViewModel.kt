package app.milkbank.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.OverviewDto
import app.milkbank.api.OverviewRowDto
import app.milkbank.core.ApiResult
import app.milkbank.data.AdminRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** All-babies overview: the summary plus rows sorted/filtered locally; a failed reload keeps the old data. */
class OverviewViewModel(
    private val admin: () -> AdminRepository,
    private val unexpectedError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _overview = MutableStateFlow<OverviewDto?>(null)
    val overview: StateFlow<OverviewDto?> = _overview.asStateFlow()
    private val _sort = MutableStateFlow(OverviewSort.STATUS)
    val sort: StateFlow<OverviewSort> = _sort.asStateFlow()
    private val _lowOnly = MutableStateFlow(false)
    val lowOnly: StateFlow<Boolean> = _lowOnly.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _rows = MutableStateFlow<List<OverviewRowDto>>(emptyList())
    /** The loaded rows with the current sort and filter applied. */
    val rows: StateFlow<List<OverviewRowDto>> = _rows.asStateFlow()

    private fun recompute() {
        _rows.value = _overview.value?.let { sortOverview(it.rows, _sort.value, _lowOnly.value) }.orEmpty()
    }

    fun setSort(sort: OverviewSort) { _sort.value = sort; recompute() }
    fun setLowOnly(lowOnly: Boolean) { _lowOnly.value = lowOnly; recompute() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                when (val r = admin().overview()) {
                    is ApiResult.Ok -> {
                        _overview.value = r.value
                        recompute()
                        _error.value = null
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
}
