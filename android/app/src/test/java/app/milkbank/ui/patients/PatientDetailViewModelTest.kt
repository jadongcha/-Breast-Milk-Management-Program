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
        PatientDetailViewModel(
            7, { patients }, { stock }, banner, session, clock, "저장했습니다", "서버에 연결할 수 없습니다",
            saveUncertainMessage = "저장이 됐는지 확인하지 못했습니다",
        )

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
        val rec = recordDto(50, staffId = 1, createdAt = clock.now())
        v.askCancel(rec)
        assertEquals(rec, v.pendingCancel.value) // the record itself is held, not looked up again later
        v.cancelRecord(50); advanceUntilIdle()
        val d = v.detail.value!!
        assertEquals(2, d.balance)
        assertTrue(d.records.first { it.id == 50 }.canceled)
        assertEquals("cancel", d.records.first().kind)
        assertNull(v.pendingCancel.value)
        assertEquals("저장했습니다", v.message.value)
    }

    @Test fun cancel_updatesBannerBalanceForSameBaby() = runTest {
        banner.post(ResultBanner("DEMO-1", "intake", 3, 8))
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.cancelRecord(50); advanceUntilIdle()
        assertEquals(2, banner.last.value!!.balance)
        assertEquals("DEMO-1", banner.last.value!!.regNo)
    }

    @Test fun cancel_leavesOtherBabyBannerAlone() = runTest {
        banner.post(ResultBanner("OTHER-9", "intake", 3, 8))
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.cancelRecord(50); advanceUntilIdle()
        assertEquals(8, banner.last.value!!.balance)
    }

    @Test fun staleRefresh_doesNotOverwriteSavedResult() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        patients.detailHandler = { gate.await(); ApiResult.Ok(detailDto(7, "DEMO-1", 5, emptyList())) } // pre-write snapshot
        v.refresh(); advanceUntilIdle()
        v.request("intake", 3, null, null); v.confirm(); advanceUntilIdle()
        assertEquals(8, v.detail.value!!.balance)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(8, v.detail.value!!.balance)
        assertEquals(100, v.detail.value!!.records.first().id)
    }

    @Test fun refreshWhileSubmitting_isSkipped() = runTest {
        val gate = CompletableDeferred<Unit>()
        stock.addHandler = { _, _, c, _, _ -> gate.await(); ApiResult.Ok(RecordResultDto(recordDto(100, count = c), 8)) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        val calls = patients.detailCalls
        v.request("intake", 3, null, null); v.confirm(); advanceUntilIdle()
        v.refresh(); advanceUntilIdle()
        assertEquals(calls, patients.detailCalls)
        gate.complete(Unit); advanceUntilIdle()
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

    @Test fun networkErrorOnAdd_refreshesFromServerAndWarnsUncertain() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        // The write reached the server but the answer was lost: the next detail shows balance 8.
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 8, listOf(recordDto(100)))) }
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        val calls = patients.detailCalls
        v.request("intake", 3, null, null); v.confirm(); advanceUntilIdle()
        assertEquals(calls + 1, patients.detailCalls)
        assertEquals(8, v.detail.value!!.balance) // the refresh was applied
        assertTrue(v.error.value!!.contains("서버에 연결할 수 없습니다"))
        assertTrue(v.error.value!!.contains("저장이 됐는지 확인하지 못했습니다"))
        assertFalse(v.submitting.value)
        assertNull(v.pending.value)
    }

    @Test fun networkErrorOnCancel_refreshesFromServerAndWarnsUncertain() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 2, emptyList())) }
        stock.cancelHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        val calls = patients.detailCalls
        v.cancelRecord(50); advanceUntilIdle()
        assertEquals(calls + 1, patients.detailCalls)
        assertEquals(2, v.detail.value!!.balance)
        assertTrue(v.error.value!!.contains("저장이 됐는지 확인하지 못했습니다"))
        assertNull(v.pendingCancel.value)
    }

    @Test fun httpConflictOnAdd_noRefreshNoUncertainMessage() = runTest {
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(409, "재고가 부족합니다 (현재 0팩)")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        val calls = patients.detailCalls
        v.request("use", 1, null, null); v.confirm(); advanceUntilIdle()
        assertEquals(calls, patients.detailCalls)
        assertEquals("재고가 부족합니다 (현재 0팩)", v.error.value)
    }

    @Test fun httpErrorOnCancel_noRefreshNoUncertainMessage() = runTest {
        stock.cancelHandler = { ApiResult.Err(ApiError(409, "이미 취소된 기록입니다")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        val calls = patients.detailCalls
        v.cancelRecord(50); advanceUntilIdle()
        assertEquals(calls, patients.detailCalls)
        assertEquals("이미 취소된 기록입니다", v.error.value)
    }

    @Test fun uncertainRefresh_isNotBlockedByTheWriteGuard() = runTest {
        // An older refresh is still pending when the write fails: the post-write refresh must still win.
        val v = vm(); v.refresh(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        patients.detailHandler = { gate.await(); ApiResult.Ok(detailDto(7, "DEMO-1", 5, emptyList())) }
        v.refresh(); advanceUntilIdle()
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 8, emptyList())) }
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(null, "x")) }
        v.request("intake", 3, null, null); v.confirm(); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(8, v.detail.value!!.balance)
    }
}
