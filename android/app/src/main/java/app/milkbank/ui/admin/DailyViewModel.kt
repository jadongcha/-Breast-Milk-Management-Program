package app.milkbank.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.milkbank.api.DailyDto
import app.milkbank.api.SeriesPointDto
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.core.SystemClock
import app.milkbank.core.todayKst
import app.milkbank.data.AdminRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Same limit as the server's `GET /admin/export.csv`. */
private const val MAX_EXPORT_DAYS = 92

/** One selectable baby on the chart: [patientId] goes to the server, only [babyId] is shown. */
data class ChartBaby(val patientId: Int, val babyId: String)

/**
 * Daily usage for one date, a 7/30-day usage series (all babies or one), and CSV export.
 * A newer request of the same kind replaces an older one still in flight.
 */
class DailyViewModel(
    private val admin: () -> AdminRepository,
    private val clock: Clock = SystemClock,
    private val csvRangeError: String = "",
    private val startAfterEnd: String = "",
    private val csvSaved: String = "",
    private val unexpectedError: String = "",
    private val csvWriteError: String = "",
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _date = MutableStateFlow(todayKst(clock.now()))
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    private val _daily = MutableStateFlow<DailyDto?>(null)
    val daily: StateFlow<DailyDto?> = _daily.asStateFlow()
    private val _days = MutableStateFlow(7)
    val days: StateFlow<Int> = _days.asStateFlow()
    private val _patientId = MutableStateFlow<Int?>(null)
    val patientId: StateFlow<Int?> = _patientId.asStateFlow()
    private val _series = MutableStateFlow<List<SeriesPointDto>>(emptyList())
    val series: StateFlow<List<SeriesPointDto>> = _series.asStateFlow()
    private val _selectedBar = MutableStateFlow<Int?>(null)
    val selectedBar: StateFlow<Int?> = _selectedBar.asStateFlow()
    private val _babies = MutableStateFlow<List<ChartBaby>>(emptyList())
    val babies: StateFlow<List<ChartBaby>> = _babies.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    /** Export problems (range, server, file write). Load failures have their own states below. */
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _dailyError = MutableStateFlow<String?>(null)
    val dailyError: StateFlow<String?> = _dailyError.asStateFlow()
    private val _seriesError = MutableStateFlow<String?>(null)
    val seriesError: StateFlow<String?> = _seriesError.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var dailyJob: Job? = null
    private var seriesJob: Job? = null

    /** Loads the table, the chart and the baby choices. A failed baby list only leaves "all" selectable. */
    fun load() {
        loadDaily()
        loadSeries()
        viewModelScope.launch {
            try {
                (admin().overview() as? ApiResult.Ok)?.let { r ->
                    _babies.value = r.value.rows.map { ChartBaby(it.patientId, it.babyId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /** The old table is dropped at once, so the new date never sits over another date's numbers. */
    fun setDate(date: LocalDate) { _date.value = date; _daily.value = null; loadDaily() }

    fun setDays(days: Int) { _days.value = days; _selectedBar.value = null; _series.value = emptyList(); loadSeries() }

    fun setPatient(patientId: Int?) {
        _patientId.value = patientId; _selectedBar.value = null; _series.value = emptyList(); loadSeries()
    }

    /** Tapping the selected bar again clears the selection. */
    fun selectBar(index: Int?) { _selectedBar.value = if (index == _selectedBar.value) null else index }

    fun clearNotice() { _notice.value = null }

    private fun loadDaily() {
        dailyJob?.cancel()
        dailyJob = viewModelScope.launch {
            guarded({ _dailyError.value = it; _daily.value = null }) {
                when (val r = admin().daily(_date.value.toString())) {
                    is ApiResult.Ok -> { _daily.value = r.value; _dailyError.value = null }
                    is ApiResult.Err -> { _daily.value = null; _dailyError.value = r.error.message }
                }
            }
        }
    }

    private fun loadSeries() {
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            guarded({ _seriesError.value = it; _series.value = emptyList() }) {
                when (val r = admin().series(_days.value, _patientId.value)) {
                    is ApiResult.Ok -> { _series.value = r.value; _seriesError.value = null }
                    is ApiResult.Err -> { _series.value = emptyList(); _seriesError.value = r.error.message }
                }
            }
        }
    }

    /** The same rules the server enforces, so a bad range never costs a request. Null when the range is fine. */
    fun exportRange(from: LocalDate, to: LocalDate): String? = when {
        from.isAfter(to) -> startAfterEnd
        ChronoUnit.DAYS.between(from, to) + 1 > MAX_EXPORT_DAYS -> csvRangeError
        else -> null
    }

    /** Fetches the CSV and hands the bytes to [write]; reports "saved" only after [write] returned normally. */
    fun export(from: LocalDate, to: LocalDate, write: suspend (ByteArray) -> Unit) {
        exportRange(from, to)?.let { _error.value = it; _notice.value = null; return }
        viewModelScope.launch {
            guarded({ _error.value = it; _notice.value = null }) {
                when (val r = admin().exportCsv(from.toString(), to.toString())) {
                    is ApiResult.Ok -> {
                        try {
                            write(r.value)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onError(e)
                            _error.value = csvWriteError
                            _notice.value = null
                            return@guarded
                        }
                        _error.value = null
                        _notice.value = csvSaved
                    }
                    is ApiResult.Err -> { _error.value = r.error.message; _notice.value = null }
                }
            }
        }
    }

    /**
     * Result of the system "save as" dialog. A null [target] means the user canceled: nothing is fetched,
     * written or reported. Otherwise the CSV for [from]..[to] is fetched and written to [target].
     */
    fun <T : Any> onDocumentChosen(target: T?, from: LocalDate, to: LocalDate, write: suspend (T, ByteArray) -> Unit) {
        if (target == null) return
        export(from, to) { bytes -> write(target, bytes) }
    }

    private suspend fun guarded(fail: (String) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            fail(unexpectedError)
        }
    }
}
