package app.milkbank.ui.admin

import app.milkbank.api.DailyDto
import app.milkbank.api.OverviewDto
import app.milkbank.api.OverviewRowDto
import app.milkbank.api.PatientDto
import app.milkbank.api.SeriesPointDto
import app.milkbank.api.SettingsDto
import app.milkbank.api.SettingsPatch
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.data.AdminRepository
import app.milkbank.ui.components.StockFormats
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
import org.junit.Before
import org.junit.Test

private class OverviewRepo : AdminRepository {
    var handler: suspend () -> ApiResult<OverviewDto> = { ApiResult.Ok(DATA) }
    var calls = 0
    override suspend fun overview(): ApiResult<OverviewDto> { calls++; return handler() }

    private val unsupported = ApiResult.Err(ApiError(null, "unsupported in fake"))
    override suspend fun daily(date: String?): ApiResult<DailyDto> = unsupported
    override suspend fun series(days: Int, patientId: Int?): ApiResult<List<SeriesPointDto>> = unsupported
    override suspend fun exportCsv(from: String, to: String): ApiResult<ByteArray> = unsupported
    override suspend fun settings(): ApiResult<SettingsDto> = unsupported
    override suspend fun saveSettings(patch: SettingsPatch): ApiResult<SettingsDto> = unsupported
    override suspend fun discharge(id: Int, remaining: String, discardReason: String?): ApiResult<PatientDto> = unsupported
    override suspend fun erase(id: Int): ApiResult<PatientDto> = unsupported

    companion object {
        private fun row(id: Int, bed: String, balance: Int, days: Double?, status: String) =
            OverviewRowDto(id, "B$id", bed, balance, days?.let { 2.0 }, days, status, 0)

        val DATA = OverviewDto(
            admitted = 3, low = 2, todayInPacks = 4, todayInMl = 320, todayUsePacks = 2, todayUseMl = null,
            pendingSubmissions = 1,
            rows = listOf(
                row(1, "A1", 9, 5.0, "enough"),
                row(2, "A2", 2, 1.0, "low"),
                row(3, "A3", 0, 0.0, "empty"),
            ),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {
    private val repo = OverviewRepo()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = OverviewViewModel({ repo }, "서버에 연결할 수 없습니다")

    private fun OverviewViewModel.ids() = rows.value.map { it.patientId }

    @Test fun load_success_rowsFollowStatusOrder_andKeepsSummary() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals(listOf(3, 2, 1), v.ids())
        assertEquals(OverviewRepo.DATA, v.overview.value)
        assertNull(v.error.value)
    }

    @Test fun changingSortOrFilter_recomputesWithoutReloading() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setSort(OverviewSort.BED)
        assertEquals(listOf(1, 2, 3), v.ids())
        v.setSort(OverviewSort.BALANCE)
        assertEquals(listOf(3, 2, 1), v.ids())
        v.setLowOnly(true)
        assertEquals(listOf(3, 2), v.ids())
        v.setSort(OverviewSort.BED)
        assertEquals(listOf(2, 3), v.ids())
        assertEquals(1, repo.calls)
    }

    @Test fun load_error_showsMessage_andKeepsPreviousRows() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        repo.handler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        v.load(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
        assertEquals(listOf(3, 2, 1), v.ids())
    }

    @Test fun load_unexpectedException_showsFallbackMessage() = runTest {
        repo.handler = { throw IllegalStateException("boom") }
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
    }

    @Test fun load_afterError_clearsError() = runTest {
        repo.handler = { ApiResult.Err(ApiError(null, "x")) }
        val v = vm(); v.load(); advanceUntilIdle()
        repo.handler = { ApiResult.Ok(OverviewRepo.DATA) }
        v.load(); advanceUntilIdle()
        assertNull(v.error.value)
    }

    // The real strings.xml, so the line wording is the one on screen.
    private val xml = java.io.File("src/main/res/values/strings.xml").readText()
    private fun res(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1].removeSurrounding("\"")
    private val formats = StockFormats(
        packs = { String.format(res("packs"), it) },
        withMl = { p, m -> String.format(res("stock_with_ml"), p, m) },
        unknownSuffix = { String.format(res("stock_unknown_suffix"), it) },
        allUnknown = { String.format(res("stock_all_unknown"), it) },
        recordPartial = { c, m, u -> String.format(res("record_ml_partial"), c, m, u) },
    )

    private fun ml(balance: Int, ml: Int, unknown: Int) =
        OverviewRowDto(1, "B1", "A1", balance, null, null, "enough", 0, ml, unknown)

    @Test fun rowStockLine_showsRemainingMl() {
        assertEquals("9팩 · 720mL", ml(9, 720, 0).stockLine(formats))
        assertEquals("9팩 · 640mL · 용량 모름 1팩", ml(9, 640, 1).stockLine(formats))
        assertEquals("2팩 · 용량 모름", ml(2, 0, 2).stockLine(formats))
        assertEquals("0팩", ml(0, 0, 0).stockLine(formats))
    }
}
