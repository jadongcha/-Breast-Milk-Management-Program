package app.milkbank.ui.admin

import app.milkbank.api.DailyDto
import app.milkbank.api.DailyTotalDto
import app.milkbank.api.OverviewDto
import app.milkbank.api.PatientDto
import app.milkbank.api.SeriesPointDto
import app.milkbank.api.SettingsDto
import app.milkbank.api.SettingsPatch
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.data.AdminRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private class DailyRepo : AdminRepository {
    val dailyCalls = mutableListOf<String?>()
    val seriesCalls = mutableListOf<Pair<Int, Int?>>()
    val exportCalls = mutableListOf<Pair<String, String>>()
    var exportResult: ApiResult<ByteArray> = ApiResult.Ok(byteArrayOf(1, 2, 3))
    var dailyFail: Boolean = false
    var seriesFail: Boolean = false

    override suspend fun daily(date: String?): ApiResult<DailyDto> {
        dailyCalls += date
        if (dailyFail) return ApiResult.Err(ApiError(500, "일별 실패"))
        return ApiResult.Ok(DailyDto(date ?: "", emptyList(), DailyTotalDto(0, null, 0, null, 0, null, 0)))
    }

    override suspend fun series(days: Int, patientId: Int?): ApiResult<List<SeriesPointDto>> {
        seriesCalls += days to patientId
        if (seriesFail) return ApiResult.Err(ApiError(500, "차트 실패"))
        return ApiResult.Ok(listOf(SeriesPointDto("2026-11-14", 1, 2, null, 0)))
    }

    override suspend fun exportCsv(from: String, to: String): ApiResult<ByteArray> {
        exportCalls += from to to
        return exportResult
    }

    private val unsupported = ApiResult.Err(ApiError(null, "unsupported in fake"))
    override suspend fun overview(): ApiResult<OverviewDto> = unsupported
    override suspend fun settings(): ApiResult<SettingsDto> = unsupported
    override suspend fun saveSettings(patch: SettingsPatch): ApiResult<SettingsDto> = unsupported
    override suspend fun discharge(id: Int, remaining: String, discardReason: String?): ApiResult<PatientDto> = unsupported
    override suspend fun erase(id: Int): ApiResult<PatientDto> = unsupported
}

@OptIn(ExperimentalCoroutinesApi::class)
class DailyViewModelTest {
    private val repo = DailyRepo()

    // 2026-11-14 16:00 UTC is already 2026-11-15 01:00 in Seoul.
    private val clock = FakeClock(Instant.parse("2026-11-14T16:00:00Z"))

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DailyViewModel(
        { repo }, clock, csvRangeError = "기간은 92일 이하로 골라 주세요",
        startAfterEnd = "시작일이 종료일보다 늦습니다", csvSaved = "CSV 파일을 저장했습니다",
        unexpectedError = "서버에 연결할 수 없습니다", csvWriteError = "파일을 저장하지 못했습니다",
    )

    @Test fun defaultDate_isTodayInKst() {
        assertEquals(LocalDate.parse("2026-11-15"), vm().date.value)
    }

    @Test fun load_callsDailyForTheDate() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals(listOf<String?>("2026-11-15"), repo.dailyCalls)
    }

    @Test fun setDate_callsDailyWithThatDate() = runTest {
        val v = vm(); v.setDate(LocalDate.parse("2026-11-01")); advanceUntilIdle()
        assertEquals(listOf<String?>("2026-11-01"), repo.dailyCalls)
        assertEquals("2026-11-01", v.daily.value?.date)
    }

    @Test fun daysAndPatient_callSeries() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals(listOf(7 to null), repo.seriesCalls)
        v.setDays(30); advanceUntilIdle()
        v.setPatient(3); advanceUntilIdle()
        assertEquals(listOf(7 to null, 30 to null, 30 to 3), repo.seriesCalls)
        assertEquals(3, v.patientId.value)
        assertEquals(30, v.days.value)
    }

    @Test fun changingChartSelection_clearsSelectedBar() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.selectBar(0)
        assertEquals(0, v.selectedBar.value)
        v.setDays(30); advanceUntilIdle()
        assertNull(v.selectedBar.value)
    }

    @Test fun exportRange_over92Days_isRejected_andRepoNotCalled() = runTest {
        val v = vm()
        val from = LocalDate.parse("2026-01-01")
        assertEquals("기간은 92일 이하로 골라 주세요", v.exportRange(from, from.plusDays(92))) // 93 days inclusive
        assertNull(v.exportRange(from, from.plusDays(91))) // 92 days inclusive
        var wrote = false
        v.export(from, from.plusDays(92)) { wrote = true }; advanceUntilIdle()
        assertTrue(repo.exportCalls.isEmpty())
        assertTrue(!wrote)
        assertEquals("기간은 92일 이하로 골라 주세요", v.error.value)
    }

    @Test fun exportRange_startAfterEnd_usesServerWording() {
        val d = LocalDate.parse("2026-02-02")
        assertEquals("시작일이 종료일보다 늦습니다", vm().exportRange(d, d.minusDays(1)))
        assertNull(vm().exportRange(d, d))
    }

    @Test fun exportCanceled_noError() = runTest {
        val v = vm()
        var wrote = false
        v.onDocumentChosen<String>(null, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-14")) { _, _ -> wrote = true }
        advanceUntilIdle()
        assertTrue(repo.exportCalls.isEmpty())
        assertTrue(!wrote)
        assertNull(v.error.value)
        assertNull(v.notice.value)
    }

    @Test fun documentChosen_writesToTarget_andShowsSaved() = runTest {
        val v = vm()
        var got: Pair<String, List<Byte>>? = null
        v.onDocumentChosen("doc", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-14")) { t, b -> got = t to b.toList() }
        advanceUntilIdle()
        assertEquals(listOf("2026-11-01" to "2026-11-14"), repo.exportCalls)
        assertEquals("doc" to listOf<Byte>(1, 2, 3), got)
        assertEquals("CSV 파일을 저장했습니다", v.notice.value)
        assertNull(v.error.value)
    }

    @Test fun export_success_writesBytes_andShowsSaved() = runTest {
        val v = vm()
        var written: ByteArray? = null
        v.export(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-14")) { written = it }; advanceUntilIdle()
        assertEquals(listOf("2026-11-01" to "2026-11-14"), repo.exportCalls)
        assertEquals(listOf<Byte>(1, 2, 3), written?.toList())
        assertEquals("CSV 파일을 저장했습니다", v.notice.value)
        assertNull(v.error.value)
    }

    @Test fun export_serverError_showsMessage_noSavedNotice() = runTest {
        repo.exportResult = ApiResult.Err(ApiError(400, "서버 메시지"))
        val v = vm()
        var wrote = false
        v.export(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-14")) { wrote = true }; advanceUntilIdle()
        assertEquals("서버 메시지", v.error.value)
        assertNull(v.notice.value)
        assertTrue(!wrote)
    }

    @Test fun failedDailyForNewDate_clearsTable_andShowsError() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals("2026-11-15", v.daily.value?.date)
        repo.dailyFail = true
        v.setDate(LocalDate.parse("2026-11-01"))
        assertNull(v.daily.value) // cleared immediately, before the load finishes
        advanceUntilIdle()
        assertNull(v.daily.value)
        assertEquals("일별 실패", v.dailyError.value)
    }

    @Test fun failedSeriesAfterSuccessfulDaily_keepsChartError() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        repo.seriesFail = true
        v.setPatient(3); advanceUntilIdle()
        assertTrue(v.series.value.isEmpty())
        assertEquals("차트 실패", v.seriesError.value)
        v.setDate(LocalDate.parse("2026-11-01")); advanceUntilIdle() // a daily success
        assertEquals("차트 실패", v.seriesError.value)
        assertNull(v.dailyError.value)
    }

    @Test fun changingChartSelection_dropsOldBarsAtOnce() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        assertTrue(v.series.value.isNotEmpty())
        v.setPatient(3)
        assertTrue(v.series.value.isEmpty())
        advanceUntilIdle()
        assertTrue(v.series.value.isNotEmpty())
    }

    @Test fun export_writeFails_showsWriteError() = runTest {
        val v = vm()
        v.export(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-14")) { throw java.io.IOException("disk") }
        advanceUntilIdle()
        assertEquals("파일을 저장하지 못했습니다", v.error.value)
        assertNull(v.notice.value)
    }
}
