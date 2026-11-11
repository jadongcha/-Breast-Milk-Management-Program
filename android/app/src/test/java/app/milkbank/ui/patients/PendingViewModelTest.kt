package app.milkbank.ui.patients

import app.milkbank.api.ConfirmResponse
import app.milkbank.api.PendingDto
import app.milkbank.api.RejectResponse
import app.milkbank.api.SubmissionDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.data.SubmissionRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private val T0 = Instant.parse("2026-11-12T05:00:00Z")

private fun pendingDto(id: Int, count: Int = 3, balance: Int = 3, discharged: Boolean = false) =
    PendingDto(id, 7, "DEMO-01", balance, "NICU 앞", T0, count, discharged)

private fun submissionDto(id: Int, status: String) = SubmissionDto(id, 7, 3, T0, status)

class FakeSubmissionRepository : SubmissionRepository {
    var items: List<PendingDto> = emptyList()
    var pendingHandler: suspend () -> ApiResult<List<PendingDto>> = { ApiResult.Ok(items) }
    var confirmHandler: suspend (Int, Int) -> ApiResult<ConfirmResponse> = { id, c ->
        ApiResult.Ok(ConfirmResponse(recordDto(100, count = c), 5, submissionDto(id, "confirmed")))
    }
    var rejectHandler: suspend (Int, String, String?) -> ApiResult<RejectResponse> =
        { id, _, _ -> ApiResult.Ok(RejectResponse(submissionDto(id, "rejected"))) }
    var pendingCalls = 0
    var confirmCalls = 0
    val rejects = mutableListOf<Triple<Int, String, String?>>()

    override suspend fun pending(): ApiResult<List<PendingDto>> { pendingCalls++; return pendingHandler() }
    override suspend fun confirm(id: Int, count: Int): ApiResult<ConfirmResponse> {
        confirmCalls++
        return confirmHandler(id, count)
    }
    override suspend fun reject(id: Int, reason: String, memo: String?): ApiResult<RejectResponse> {
        rejects += Triple(id, reason, memo)
        return rejectHandler(id, reason, memo)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PendingViewModelTest {
    private val repo = FakeSubmissionRepository()
    private val banner = ResultBannerStore()
    private val clock = FakeClock(T0)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repo.items = listOf(pendingDto(1))
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PendingViewModel({ repo }, banner, clock, "입고 확인", "처리됐는지 확인하지 못했습니다", "서버에 연결할 수 없습니다")

    @Test fun refresh_loadsItems() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        assertEquals(listOf(pendingDto(1)), v.items.value)
    }

    @Test fun confirm_postsBannerAndShowsDone() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.openConfirm(v.items.value.single())
        assertEquals(3, (v.dialog.value as PendingDialog.Confirm).count)
        v.dec()
        v.confirm(); advanceUntilIdle()
        assertEquals(ResultBanner("DEMO-01", "intake_confirm", 2, 5), banner.last.value)
        assertEquals(PendingDialog.Done(pendingDto(1), received = 2, balance = 5, before = 3), v.dialog.value)
    }

    @Test fun confirm_refreshesTableAfterSuccess() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        repo.items = emptyList()
        v.openConfirm(v.items.value.single()); v.confirm(); advanceUntilIdle()
        assertTrue(v.items.value.isEmpty())
    }

    @Test fun count_clampedTo1to100() = runTest {
        repo.items = listOf(pendingDto(1, count = 99))
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.openConfirm(v.items.value.single())
        v.inc(); v.inc(); v.inc()
        assertEquals(100, (v.dialog.value as PendingDialog.Confirm).count)
        repeat(150) { v.dec() }
        assertEquals(1, (v.dialog.value as PendingDialog.Confirm).count)
    }

    @Test fun confirmTwiceWhileSubmitting_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.confirmHandler = { id, c ->
            gate.await()
            ApiResult.Ok(ConfirmResponse(recordDto(100, count = c), 5, submissionDto(id, "confirmed")))
        }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.openConfirm(v.items.value.single())
        v.confirm(); v.confirm(); advanceUntilIdle()
        assertEquals(1, repo.confirmCalls)
        v.inc() // count frozen while in flight
        assertEquals(3, (v.dialog.value as PendingDialog.Confirm).count)
        gate.complete(Unit); advanceUntilIdle()
        v.confirm(); advanceUntilIdle() // dialog is Done now: ignored
        assertEquals(1, repo.confirmCalls)
    }

    @Test fun confirm409_showsServerMessageAndRefreshes() = runTest {
        repo.confirmHandler = { _, _ -> ApiResult.Err(ApiError(409, "이미 처리된 제출입니다")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        val before = repo.pendingCalls
        repo.items = emptyList()
        v.openConfirm(v.items.value.single()); v.confirm(); advanceUntilIdle()
        assertEquals("이미 처리된 제출입니다", v.error.value)
        assertNull(v.dialog.value)
        assertTrue(repo.pendingCalls > before)
        assertTrue(v.items.value.isEmpty())
        assertNull(banner.last.value)
    }

    @Test fun confirmNetworkError_refreshesAndWarns() = runTest {
        repo.confirmHandler = { _, _ -> ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        val before = repo.pendingCalls
        v.openConfirm(v.items.value.single()); v.confirm(); advanceUntilIdle()
        assertEquals("처리됐는지 확인하지 못했습니다", v.error.value)
        assertNull(v.dialog.value)
        assertTrue(repo.pendingCalls > before)
        assertNull(banner.last.value)
    }

    @Test fun reject_otherWithoutMemo_notSent() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.openReject(v.items.value.single())
        v.setReason(REJECT_OTHER); v.setMemo("   ")
        v.reject(); advanceUntilIdle()
        assertTrue(repo.rejects.isEmpty())
        assertTrue(v.dialog.value is PendingDialog.Reject)
    }

    @Test fun reject_withoutReason_notSent() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.openReject(v.items.value.single())
        v.reject(); advanceUntilIdle()
        assertTrue(repo.rejects.isEmpty())
    }

    @Test fun reject_notReceived_sendsAndRefreshes() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        val before = repo.pendingCalls
        repo.items = emptyList()
        v.openReject(v.items.value.single())
        v.setReason(REJECT_NOT_RECEIVED); v.setMemo("무시됨")
        v.reject(); advanceUntilIdle()
        assertEquals(listOf(Triple(1, "not_received", null as String?)), repo.rejects)
        assertNull(v.dialog.value)
        assertTrue(repo.pendingCalls > before)
        assertTrue(v.items.value.isEmpty())
    }

    @Test fun reject_other_sendsTrimmedMemo() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.openReject(v.items.value.single())
        v.setReason(REJECT_OTHER); v.setMemo(" 수량 불일치 ")
        v.reject(); advanceUntilIdle()
        assertEquals(listOf(Triple(1, "other", "수량 불일치" as String?)), repo.rejects)
    }

    @Test fun dischargedItem_cannotOpenConfirm() = runTest {
        val v = vm()
        v.openConfirm(pendingDto(1, discharged = true))
        assertNull(v.dialog.value)
        v.openReject(pendingDto(1, discharged = true))
        assertTrue(v.dialog.value is PendingDialog.Reject)
    }

    @Test fun bannerChange_refreshes() = runTest {
        val v = vm(); advanceUntilIdle()
        val before = repo.pendingCalls
        banner.notifyChanged(); advanceUntilIdle()
        assertTrue(repo.pendingCalls > before)
    }

    @Test fun refreshFailure_showsServerMessage() = runTest {
        repo.pendingHandler = { ApiResult.Err(ApiError(500, "서버 오류")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        assertEquals("서버 오류", v.refreshError.value)
        assertNull(v.error.value)
        repo.pendingHandler = { ApiResult.Ok(repo.items) }
        v.refresh(); advanceUntilIdle()
        assertNull(v.refreshError.value)
    }

    @Test fun confirmUncertainAndRefreshAlsoFails_warningStaysEvenAfterLaterRefreshSucceeds() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        repo.confirmHandler = { _, _ -> ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        repo.pendingHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        v.openConfirm(v.items.value.single()); v.confirm(); advanceUntilIdle()
        assertEquals("처리됐는지 확인하지 못했습니다", v.error.value)
        assertEquals("서버에 연결할 수 없습니다", v.refreshError.value)
        repo.pendingHandler = { ApiResult.Ok(repo.items) }
        v.refresh(); advanceUntilIdle()
        assertEquals("처리됐는지 확인하지 못했습니다", v.error.value)
        assertNull(v.refreshError.value)
        v.openReject(v.items.value.single()) // next dialog clears it
        assertNull(v.error.value)
    }

    @Test fun confirm409AndRefreshFails_serverMessageStays() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        repo.confirmHandler = { _, _ -> ApiResult.Err(ApiError(409, "이미 처리된 제출입니다")) }
        repo.pendingHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        v.openConfirm(v.items.value.single()); v.confirm(); advanceUntilIdle()
        assertEquals("이미 처리된 제출입니다", v.error.value)
        assertEquals("서버에 연결할 수 없습니다", v.refreshError.value)
    }
}
