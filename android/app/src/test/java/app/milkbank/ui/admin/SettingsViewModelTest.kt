package app.milkbank.ui.admin

import app.milkbank.api.DailyDto
import app.milkbank.api.OverviewDto
import app.milkbank.api.PatientDto
import app.milkbank.api.SeriesPointDto
import app.milkbank.api.SettingsDto
import app.milkbank.api.SettingsPatch
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeAdminRepository : AdminRepository {
    var settingsHandler: suspend () -> ApiResult<SettingsDto> =
        { ApiResult.Ok(SettingsDto(3, 6, 7, 10, 30)) }
    var saveHandler: suspend (SettingsPatch) -> ApiResult<SettingsDto> = { p ->
        ApiResult.Ok(
            SettingsDto(
                p.daysThreshold ?: 3, p.packsThreshold ?: 6, p.avgDays ?: 7,
                p.staffIdleMinutes ?: 10, p.retentionDays ?: 30,
            ),
        )
    }
    val saved = mutableListOf<SettingsPatch>()

    override suspend fun settings() = settingsHandler()
    override suspend fun saveSettings(patch: SettingsPatch): ApiResult<SettingsDto> {
        saved += patch; return saveHandler(patch)
    }

    private val unsupported = ApiResult.Err(ApiError(null, "unsupported in fake"))
    override suspend fun overview(): ApiResult<OverviewDto> = unsupported
    override suspend fun daily(date: String?): ApiResult<DailyDto> = unsupported
    override suspend fun series(days: Int, patientId: Int?): ApiResult<List<SeriesPointDto>> = unsupported
    override suspend fun exportCsv(from: String, to: String): ApiResult<ByteArray> = unsupported
    override suspend fun discharge(id: Int, remaining: String, discardReason: String?): ApiResult<PatientDto> = unsupported
    override suspend fun erase(id: Int): ApiResult<PatientDto> = unsupported
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repo = FakeAdminRepository()
    private val idleChanges = mutableListOf<Int>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SettingsViewModel({ repo }, { idleChanges += it }, "서버에 연결할 수 없습니다")

    @Test fun load_fillsTheFiveFields() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals(
            mapOf(
                SettingKey.DAYS_THRESHOLD to "3", SettingKey.PACKS_THRESHOLD to "6", SettingKey.AVG_DAYS to "7",
                SettingKey.STAFF_IDLE_MINUTES to "10", SettingKey.RETENTION_DAYS to "30",
            ),
            v.fields.value,
        )
    }

    @Test fun save_sendsOnlyTheChangedField_andShowsSaved() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.AVG_DAYS, "5")
        v.save(); advanceUntilIdle()
        assertEquals(listOf(SettingsPatch(avgDays = 5)), repo.saved)
        assertTrue(v.saved.value)
        assertEquals("5", v.fields.value[SettingKey.AVG_DAYS])
        assertTrue(idleChanges.isEmpty())
    }

    @Test fun save_serverError_showsMessageAsIs() = runTest {
        repo.saveHandler = { ApiResult.Err(ApiError(400, "평균 계산 기간은 1~30일입니다")) }
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.AVG_DAYS, "99")
        v.save(); advanceUntilIdle()
        assertEquals("평균 계산 기간은 1~30일입니다", v.error.value)
        assertFalse(v.saved.value)
        assertEquals("99", v.fields.value[SettingKey.AVG_DAYS])
    }

    @Test fun save_nonNumericField_doesNotCallTheServer() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.AVG_DAYS, "5a")
        v.setField(SettingKey.DAYS_THRESHOLD, "")
        assertEquals(setOf(SettingKey.AVG_DAYS, SettingKey.DAYS_THRESHOLD), v.invalid.value)
        v.save(); advanceUntilIdle()
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun save_nothingChanged_doesNotCallTheServer() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.save(); advanceUntilIdle()
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun save_changedIdleMinutes_updatesTheSession() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.STAFF_IDLE_MINUTES, "20")
        v.save(); advanceUntilIdle()
        assertEquals(listOf(20), idleChanges)
    }

    @Test fun save_lowerRetention_asksFirst_thenSendsOnConfirm() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.RETENTION_DAYS, "10")
        v.save(); advanceUntilIdle()
        assertTrue(v.confirmRetention.value)
        assertTrue(repo.saved.isEmpty())
        v.confirmRetentionSave(); advanceUntilIdle()
        assertFalse(v.confirmRetention.value)
        assertEquals(listOf(SettingsPatch(retentionDays = 10)), repo.saved)
        assertTrue(v.saved.value)
    }

    @Test fun save_lowerRetention_dismissSendsNothing() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.RETENTION_DAYS, "10")
        v.save(); advanceUntilIdle()
        v.dismissRetentionConfirm(); advanceUntilIdle()
        assertFalse(v.confirmRetention.value)
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun save_equalOrHigherRetention_savesDirectly() = runTest {
        val v = vm(); v.load(); advanceUntilIdle()
        v.setField(SettingKey.RETENTION_DAYS, "60")
        v.save(); advanceUntilIdle()
        assertFalse(v.confirmRetention.value)
        assertEquals(listOf(SettingsPatch(retentionDays = 60)), repo.saved)
        // 60 is now the loaded value; saving it again changes nothing.
        v.save(); advanceUntilIdle()
        assertEquals(1, repo.saved.size)
    }

    @Test fun load_error_showsMessage() = runTest {
        repo.settingsHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        val v = vm(); v.load(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
        assertNull(v.fields.value[SettingKey.AVG_DAYS])
    }
}
