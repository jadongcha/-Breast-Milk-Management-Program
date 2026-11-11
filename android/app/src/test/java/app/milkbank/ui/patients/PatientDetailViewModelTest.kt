package app.milkbank.ui.patients

import app.milkbank.api.RecordResultDto
import app.milkbank.api.StaffDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
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
class PatientDetailViewModelTest {
    private val patients = FakePatientRepository()
    private val stock = FakeStockRepository()
    private val banner = ResultBannerStore()
    private val clock = FakeClock(Instant.parse("2026-11-10T01:00:00Z"))
    private val session = SessionManager(clock)
    private val staff = StaffDto(1, "n", "김간호", "nurse", true, false)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        session.start("tok", staff)
        patients.detailHandler = {
            ApiResult.Ok(detailDto(7, "DEMO-1", 5, listOf(recordDto(50, staffId = 1, createdAt = clock.now()))))
        }
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() =
        PatientDetailViewModel(7, { patients }, { stock }, banner, session, clock, "저장했습니다", "서버에 연결할 수 없습니다")

    @Test fun intake_success_updatesBalanceRecordsSavedAndBanner() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.request("intake", 3, null, null)
        assertEquals(PendingAction("intake", 3, null, null), v.pending.value)
        v.confirm(); advanceUntilIdle()
        assertEquals(8, v.detail.value!!.balance)
        assertEquals(100, v.detail.value!!.records.first().id)
        assertEquals("저장했습니다", v.message.value)
        assertNull(v.pending.value)
        assertFalse(v.submitting.value)
        assertEquals(ResultBanner("DEMO-1", "intake", 3, 8), banner.last.value)
    }

    @Test fun conflict_showsServerMessage_noBanner() = runTest {
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(409, "재고가 부족합니다 (현재 0팩)")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.request("use", 1, null, null); v.confirm(); advanceUntilIdle()
        assertEquals("재고가 부족합니다 (현재 0팩)", v.error.value)
        assertEquals(5, v.detail.value!!.balance)
        assertNull(v.pending.value)
        assertNull(banner.last.value)
        assertFalse(v.submitting.value)
    }

    @Test fun submitWhileSubmitting_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        stock.addHandler = { _, _, c, _, _ -> gate.await(); ApiResult.Ok(RecordResultDto(recordDto(100, count = c), 8)) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.request("intake", 3, null, null)
        v.confirm(); v.confirm(); advanceUntilIdle()
        assertTrue(v.submitting.value)
        v.confirm(); v.request("intake", 9, null, null); advanceUntilIdle()
        assertEquals(1, stock.addCalls)
        assertEquals(3, v.pending.value!!.count)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, stock.addCalls)
        assertEquals(8, v.detail.value!!.balance)
    }

    @Test fun unauthorizedDuringSubmit_logsOut() = runTest {
        stock.addHandler = { _, _, _, _, _ ->
            session.end(LogoutReason.EXPIRED) // what AuthInterceptor does on a 401
            ApiResult.Err(ApiError(401, "다시 로그인해 주세요"))
        }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.request("intake", 3, null, null); v.confirm(); advanceUntilIdle()
        assertNull(session.state.value)
        assertEquals(LogoutReason.EXPIRED, session.logoutReason.value)
        assertNull(v.pending.value)
        assertFalse(v.submitting.value)
        assertNull(v.message.value)
        assertNull(banner.last.value)
    }

    @Test fun invalidRequests_areIgnored() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.request("intake", 0, null, null)
        assertNull(v.pending.value)
        v.request("intake", 11, null, null)
        assertNull(v.pending.value)
        v.request("discard", 1, null, null) // reason required
        assertNull(v.pending.value)
        v.request("discard", 1, "contaminated", null)
        assertEquals("contaminated", v.pending.value!!.reason)
    }

    @Test fun dismissPending_clears_butNotWhileSubmitting() = runTest {
        val gate = CompletableDeferred<Unit>()
        stock.addHandler = { _, _, c, _, _ -> gate.await(); ApiResult.Ok(RecordResultDto(recordDto(100, count = c), 8)) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.request("intake", 1, null, null); v.dismissPending()
        assertNull(v.pending.value)
        v.request("intake", 1, null, null); v.confirm(); advanceUntilIdle()
        v.dismissPending()
        assertEquals(1, v.pending.value!!.count)
        gate.complete(Unit); advanceUntilIdle()
    }

    @Test fun cancel_success_updatesBalanceAndMarksTarget() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.askCancel(50)
        assertEquals(50, v.pendingCancel.value)
        v.cancelRecord(50); advanceUntilIdle()
        val d = v.detail.value!!
        assertEquals(2, d.balance)
        assertTrue(d.records.first { it.id == 50 }.canceled)
        assertEquals("cancel", d.records.first().kind)
        assertNull(v.pendingCancel.value)
        assertEquals("저장했습니다", v.message.value)
    }

    @Test fun cancelTwice_isIgnoredWhileSubmitting() = runTest {
        val gate = CompletableDeferred<Unit>()
        stock.cancelHandler = { id ->
            gate.await()
            ApiResult.Ok(RecordResultDto(recordDto(101, kind = "cancel", targetId = id), 2))
        }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.cancelRecord(50); v.cancelRecord(50); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, stock.cancelCalls)
    }

    @Test fun refreshError_isShown() = runTest {
        patients.detailHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
    }

    @Test fun canCancel_usesSessionAndClock() = runTest {
        val v = vm()
        assertTrue(v.canCancel(recordDto(1, staffId = 1, createdAt = clock.now())))
        assertFalse(v.canCancel(recordDto(1, staffId = 2, createdAt = clock.now())))
        session.end(LogoutReason.USER)
        assertFalse(v.canCancel(recordDto(1, staffId = 1, createdAt = clock.now())))
    }
}
