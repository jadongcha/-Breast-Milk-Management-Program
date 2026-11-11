package app.milkbank.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.PatientDto
import app.milkbank.core.ApiResult
import app.milkbank.data.PatientRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class PatientListViewModel(
    private val patientRepo: () -> PatientRepository,
    banner: ResultBannerStore,
    private val debounceMs: Long = 250,
    private val unexpectedError: String = "",
) : ViewModel() {
    private val _patients = MutableStateFlow<List<PatientDto>>(emptyList())
    val patients: StateFlow<List<PatientDto>> = _patients.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _match = MutableStateFlow<BabyMatch>(BabyMatch.Empty)
    val match: StateFlow<BabyMatch> = _match.asStateFlow()
    val lastResult: StateFlow<ResultBanner?> = banner.last

    private var refreshJob: Job? = null
    private var debounceJob: Job? = null

    init {
        // Stock changed elsewhere (detail screen, two-pane): reload. The first value is the initial one.
        viewModelScope.launch { banner.changes.drop(1).collect { refresh() } }
    }

    fun toggleExpanded() {
        _expanded.value = !_expanded.value
    }

    fun onQueryChange(q: String) {
        _query.value = q
        debounceJob?.cancel()
        if (q.isBlank()) {
            recompute()
            return
        }
        debounceJob = viewModelScope.launch {
            delay(debounceMs)
            recompute()
        }
    }

    private fun recompute() {
        _match.value = matchBabies(_query.value, _patients.value)
    }

    fun refresh() {
        refreshJob?.cancel()
        _loading.value = true
        refreshJob = viewModelScope.launch {
            try {
                when (val r = patientRepo().list(null)) {
                    is ApiResult.Ok -> {
                        _patients.value = r.value
                        _error.value = null
                        recompute()
                    }
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = unexpectedError
            } finally {
                _loading.value = false
            }
        }
    }
}
