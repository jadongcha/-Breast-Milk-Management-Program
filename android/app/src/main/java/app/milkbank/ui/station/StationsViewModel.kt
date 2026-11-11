package app.milkbank.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.StationDto
import app.milkbank.core.ApiResult
import app.milkbank.data.StationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StationsViewModel(
    private val stations: () -> StationRepository,
    private val unexpectedError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _items = MutableStateFlow<List<StationDto>>(emptyList())
    val items: StateFlow<List<StationDto>> = _items.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refresh() {
        viewModelScope.launch { load() }
    }

    /** The confirm dialog is the screen's job; this deactivates and reloads the list. */
    fun deactivate(id: Int) {
        if (!_busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val r = stations().deactivate(id)) {
                    is ApiResult.Ok -> load()
                    is ApiResult.Err -> _error.value = r.error.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                _error.value = unexpectedError
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun load() {
        try {
            when (val r = stations().list()) {
                is ApiResult.Ok -> {
                    _items.value = r.value
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
