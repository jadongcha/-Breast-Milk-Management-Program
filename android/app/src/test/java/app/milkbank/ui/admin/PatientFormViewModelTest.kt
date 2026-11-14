package app.milkbank.ui.admin

import app.milkbank.api.PatientForm
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.ui.patients.FakePatientRepository
import app.milkbank.ui.patients.ResultBanner
import app.milkbank.ui.patients.ResultBannerStore
import app.milkbank.ui.patients.detailDto
import app.milkbank.ui.patients.patientDto
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PatientFormViewModelTest {
    private val repo = FakePatientRepository()
    private val admin = FakeAdminRepository()
    private val banner = ResultBannerStore()

    // 2026-11-10 16:00 UTC is already 2026-11-11 in Seoul.
    private val clock = FakeClock(Instant.parse("2026-11-10T16:00:00Z"))

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(id: Int? = null) = PatientFormViewModel(id, { repo }, { admin }, banner, clock, "서버에 연결할 수 없습니다")

    private fun PatientFormViewModel.fill() {
        setRegNo("DEMO-9"); setBabyName("김아기"); setBed("4"); setGuardianName("박보호"); setGuardianPhone("01012345678")
    }

    @Test fun create_success_sendsFormWithDefaultsAndSignalsSaved() = runTest {
        val v = vm(); v.fill()
        assertEquals("2026-11-11", v.form.value.admittedOn)
        v.submit(); advanceUntilIdle()
        assertEquals(
            PatientForm("DEMO-9", "김아기", "4", "박보호", "01012345678", "2026-11-11", true, null, null),
            repo.createdForms.single(),
        )
        assertTrue(v.saved.value)
        assertNull(v.error.value)
        assertFalse(v.submitting.value)
        assertEquals(1L, banner.changes.value)
    }

    @Test fun create_usesSelectedDate() = runTest {
        val v = vm(); v.fill(); v.setAdmittedOn("2026-11-01")
        v.submit(); advanceUntilIdle()
        assertEquals("2026-11-01", repo.createdForms.single().admittedOn)
    }

    @Test fun conflict_showsServerMessage_notSaved() = runTest {
        repo.createHandler = { ApiResult.Err(ApiError(409, "이미 등록된 등록번호입니다")) }
        val v = vm(); v.fill()
        v.submit(); advanceUntilIdle()
        assertEquals("이미 등록된 등록번호입니다", v.error.value)
        assertFalse(v.saved.value)
        assertFalse(v.submitting.value)
        assertEquals(0L, banner.changes.value)
    }

    @Test fun phoneFormatMessage_isShownVerbatim() = runTest {
        repo.createHandler = { ApiResult.Err(ApiError(422, "전화번호 형식이 올바르지 않습니다")) }
        val v = vm(); v.fill()
        v.submit(); advanceUntilIdle()
        assertEquals("전화번호 형식이 올바르지 않습니다", v.error.value)
    }

    @Test fun edit_prefillsFromDetail_andKeepsAlertSettings() = runTest {
        repo.detailHandler = {
            ApiResult.Ok(
                detailDto(it, "DEMO-1").copy(
                    babyName = "원래이름", alertEnabled = false, daysThreshold = 3, packsThreshold = 5,
                    admittedOn = "2026-10-20",
                ),
            )
        }
        val v = vm(7); advanceUntilIdle()
        assertEquals("DEMO-1", v.form.value.regNo)
        assertEquals("원래이름", v.form.value.babyName)
        assertEquals("2026-10-20", v.form.value.admittedOn)
        v.setBed("9")
        v.submit(); advanceUntilIdle()
        val (id, f) = repo.updatedForms.single()
        assertEquals(7, id)
        assertEquals(PatientForm("DEMO-1", "원래이름", "9", "보호자", "010-1", "2026-10-20", false, 3, 5), f)
        assertTrue(v.saved.value)
        assertEquals(0, repo.createdForms.size)
    }

    @Test fun edit_submitBeforeLoaded_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.detailHandler = { gate.await(); ApiResult.Ok(detailDto(it)) }
        val v = vm(7); v.fill()
        v.submit(); advanceUntilIdle()
        assertEquals(0, repo.updatedForms.size)
        gate.complete(Unit); advanceUntilIdle()
    }

    @Test fun edit_loadFailure_showsServerMessage() = runTest {
        repo.detailHandler = { ApiResult.Err(ApiError(404, "환자를 찾을 수 없습니다")) }
        val v = vm(7); advanceUntilIdle()
        assertEquals("환자를 찾을 수 없습니다", v.error.value)
    }

    @Test fun submitWhileSubmitting_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.createHandler = { gate.await(); ApiResult.Ok(patientDto(1, it.regNo)) }
        val v = vm(); v.fill()
        v.submit(); v.submit(); advanceUntilIdle()
        assertTrue(v.submitting.value)
        v.submit(); advanceUntilIdle()
        assertEquals(1, repo.createdForms.size)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, repo.createdForms.size)
        assertFalse(v.submitting.value)
    }

    @Test fun unexpectedException_showsFallbackAndReleasesLock() = runTest {
        repo.createHandler = { error("boom") }
        val v = vm(); v.fill()
        v.submit(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
        assertFalse(v.submitting.value)
    }

    private fun loaded(
        status: String = "admitted", balance: Int = 5, erasedAt: String? = null,
    ) {
        repo.detailHandler = { ApiResult.Ok(detailDto(it, "DEMO-1", balance).copy(status = status, erasedAt = erasedAt)) }
    }

    @Test fun discharge_return_callsRepository_postsBannerAndSignalsSaved() = runTest {
        loaded(balance = 4)
        admin.dischargeHandler = { id, _, _ -> ApiResult.Ok(patientDto(id, "DEMO-1")) }
        val v = vm(7); advanceUntilIdle()
        v.discharge("return", null); advanceUntilIdle()
        assertEquals(listOf(Triple(7, "return", null as String?)), admin.discharges)
        assertTrue(v.saved.value)
        assertEquals(ResultBanner("DEMO-1", "discharge_return", 4, 0), banner.last.value)
        assertFalse(v.submitting.value)
    }

    @Test fun discharge_discard_sendsReason() = runTest {
        loaded()
        admin.dischargeHandler = { id, _, _ -> ApiResult.Ok(patientDto(id, "DEMO-1")) }
        val v = vm(7); advanceUntilIdle()
        v.discharge("discard", "contaminated"); advanceUntilIdle()
        assertEquals(listOf(Triple(7, "discard", "contaminated" as String?)), admin.discharges)
        assertEquals("discard", banner.last.value?.kind)
    }

    @Test fun discharge_discardNeedsReason() = runTest {
        loaded()
        val v = vm(7); advanceUntilIdle()
        v.discharge("discard", null); advanceUntilIdle()
        assertEquals(0, admin.discharges.size)
        assertFalse(v.saved.value)
    }

    @Test fun discharge_whenAlreadyDischarged_isIgnored() = runTest {
        loaded(status = "discharged")
        val v = vm(7); advanceUntilIdle()
        v.discharge("return", null); advanceUntilIdle()
        assertEquals(0, admin.discharges.size)
    }

    @Test fun discharge_serverConflict_showsMessage_andClosesDialog() = runTest {
        loaded()
        admin.dischargeHandler = { _, _, _ -> ApiResult.Err(ApiError(409, "이미 퇴원한 환자입니다")) }
        val v = vm(7); advanceUntilIdle()
        v.openDischarge()
        v.discharge("return", null); advanceUntilIdle()
        assertEquals("이미 퇴원한 환자입니다", v.error.value)
        assertFalse(v.saved.value)
        assertFalse(v.dischargeDialog.value)
        assertNull(banner.last.value)
    }

    @Test fun dialogs_openAndClose_onlyWhenAllowed() = runTest {
        loaded()
        val v = vm(7); advanceUntilIdle()
        v.openDischarge(); assertTrue(v.dischargeDialog.value)
        v.closeDischarge(); assertFalse(v.dischargeDialog.value)
        v.openErase() // admitted: not allowed
        assertFalse(v.eraseDialog.value)
    }

    @Test fun erase_onlyWhenDischarged() = runTest {
        loaded(status = "admitted")
        val v = vm(7); advanceUntilIdle()
        v.erase(); advanceUntilIdle()
        assertEquals(0, admin.erases.size)
        assertFalse(v.saved.value)
    }

    @Test fun erase_whenDischarged_callsRepositoryAndSignalsSaved() = runTest {
        loaded(status = "discharged", balance = 0)
        admin.eraseHandler = { id -> ApiResult.Ok(patientDto(id, "DEMO-1")) }
        val v = vm(7); advanceUntilIdle()
        v.openErase(); assertTrue(v.eraseDialog.value)
        v.erase(); advanceUntilIdle()
        assertEquals(listOf(7), admin.erases)
        assertTrue(v.saved.value)
        assertFalse(v.eraseDialog.value)
        assertEquals(1L, banner.changes.value)
    }

    @Test fun erase_serverConflict_showsMessage() = runTest {
        loaded(status = "discharged")
        admin.eraseHandler = { ApiResult.Err(ApiError(409, "이미 말소된 환자입니다")) }
        val v = vm(7); advanceUntilIdle()
        v.erase(); advanceUntilIdle()
        assertEquals("이미 말소된 환자입니다", v.error.value)
        assertFalse(v.saved.value)
    }

    @Test fun erasedPatient_isReadOnly() = runTest {
        loaded(status = "discharged", erasedAt = "2026-11-10T00:00:00Z")
        val v = vm(7); advanceUntilIdle()
        assertTrue(v.erased.value)
        v.setBed("9")
        v.submit(); advanceUntilIdle()
        v.erase(); advanceUntilIdle()
        assertEquals(0, repo.updatedForms.size)
        assertEquals(0, admin.erases.size)
        assertFalse(v.saved.value)
    }

    @Test fun admittedPatient_isNotErased() = runTest {
        loaded()
        val v = vm(7); advanceUntilIdle()
        assertFalse(v.erased.value)
        assertTrue(v.canDischarge.value)
        assertFalse(v.canErase.value)
    }
}
