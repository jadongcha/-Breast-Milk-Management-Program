package app.milkbank.ui.patients

import app.milkbank.api.PackDto
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

    private val t = Instant.parse("2026-11-10T00:00:00Z")
    private val packs = listOf(PackDto(1, 80, t), PackDto(2, 80, t), PackDto(3, null, t), PackDto(4, 100, t))

    private fun vmWithPacks(): PatientDetailViewModel {
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 4, packs = packs).copy(balanceMl = 260, unknownMlPacks = 1)) }
        return vm()
    }

    @Test fun detail_keepsRemainingPacksAndVolumes() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4), v.detail.value!!.packs.map { it.id })
        assertEquals(260, v.detail.value!!.balanceMl)
        assertEquals(1, v.detail.value!!.unknownMlPacks)
    }

    @Test fun intake_sendsPackMls() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("intake"); v.setCount(3)
        v.setPackMls(v.dialog.value!!.packMls.setAt(0, "80").setAt(1, "80"))
        v.submitDialog()
        assertEquals(listOf<Int?>(80, 80, null), v.pending.value!!.packMls)
        assertNull(v.dialog.value)
        v.confirm(); advanceUntilIdle()
        assertEquals(listOf<List<Int?>?>(listOf(80, 80, null)), stock.addPackMls)
        assertEquals(listOf<List<Int>?>(null), stock.addPackIds)
    }

    @Test fun intake_sameVolumeForAll_fillsEveryPack() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("intake"); v.setCount(2)
        v.setPackMls(v.dialog.value!!.packMls.setAll("90"))
        v.submitDialog(); v.confirm(); advanceUntilIdle()
        assertEquals(listOf<List<Int?>?>(listOf(90, 90)), stock.addPackMls)
    }

    @Test fun intake_invalidBlocksSave() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("intake"); v.setCount(2)
        v.setPackMls(v.dialog.value!!.packMls.setAt(1, "0"))
        assertFalse(v.dialog.value!!.canSubmit)
        v.submitDialog()
        assertNull(v.pending.value)
        assertEquals("intake", v.dialog.value!!.kind) // stays open
        v.setPackMls(v.dialog.value!!.packMls.setAt(1, "").setAll("abc")) // an invalid "all" field blocks too
        assertFalse(v.dialog.value!!.canSubmit)
        v.submitDialog()
        assertNull(v.pending.value)
        assertEquals(0, stock.addCalls)
    }

    @Test fun intake_needsCount() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("intake")
        assertFalse(v.dialog.value!!.canSubmit)
        v.submitDialog()
        assertNull(v.pending.value)
    }

    @Test fun intakeCountChange_resizesFields() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("intake"); v.setCount(3)
        v.setPackMls(v.dialog.value!!.packMls.setAt(0, "80"))
        v.setCount(2)
        assertEquals(listOf("80", ""), v.dialog.value!!.packMls.texts)
        v.setCount(4)
        assertEquals(listOf("80", "", "", ""), v.dialog.value!!.packMls.texts)
    }

    @Test fun use_previewIsOldestPacks() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use")
        assertEquals(listOf(1), v.dialog.value!!.previewPacks.map { it.id })
        v.setCount(3)
        assertEquals(listOf(1, 2, 3), v.dialog.value!!.previewPacks.map { it.id })
        assertNull(v.dialog.value!!.chosenPackIds)
    }

    @Test fun choosePacks_requiresExactCount() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(2)
        v.openChoosePacks()
        assertTrue(v.dialog.value!!.choosing)
        v.togglePack(3)
        v.finishChoosePacks() // 1 of 2: not enough
        assertTrue(v.dialog.value!!.choosing)
        assertNull(v.dialog.value!!.chosenPackIds)
        v.togglePack(4); v.togglePack(1) // a third pick is ignored
        assertEquals(2, v.dialog.value!!.picking!!.size)
        v.finishChoosePacks()
        assertFalse(v.dialog.value!!.choosing)
        assertEquals(listOf(3, 4), v.dialog.value!!.chosenPackIds)
        assertEquals(listOf(3, 4), v.dialog.value!!.previewPacks.map { it.id })
    }

    @Test fun choosePacks_untickingFreesASlot() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(1)
        v.openChoosePacks(); v.togglePack(2); v.togglePack(2); v.togglePack(4)
        v.finishChoosePacks()
        assertEquals(listOf(4), v.dialog.value!!.chosenPackIds)
    }

    @Test fun choosePacks_sendsPackIds() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(2)
        v.openChoosePacks(); v.togglePack(4); v.togglePack(3); v.finishChoosePacks()
        v.submitDialog()
        assertEquals(listOf(3, 4), v.pending.value!!.packIds)
        v.confirm(); advanceUntilIdle()
        assertEquals(listOf<List<Int>?>(listOf(3, 4)), stock.addPackIds)
        assertEquals(listOf<List<Int?>?>(null), stock.addPackMls)
    }

    @Test fun choosePacks_countChangeForgetsTheChoice() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(2)
        v.openChoosePacks(); v.togglePack(3); v.togglePack(4); v.finishChoosePacks()
        v.setCount(3)
        assertNull(v.dialog.value!!.chosenPackIds)
        assertEquals(listOf(1, 2, 3), v.dialog.value!!.previewPacks.map { it.id })
    }

    @Test fun use_withoutChoosing_sendsNullPackIds() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(2)
        v.submitDialog(); v.confirm(); advanceUntilIdle()
        assertEquals(listOf<List<Int>?>(null), stock.addPackIds)
        assertEquals(listOf<List<Int?>?>(null), stock.addPackMls)
    }

    @Test fun use_confirmationListsThePacksThatLeave() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(2)
        v.submitDialog()
        assertEquals(listOf(1, 2), v.pending.value!!.goingPacks.map { it.id })
    }

    @Test fun discard_needsReason_andCanChoosePacks() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("discard"); v.setCount(1)
        assertFalse(v.dialog.value!!.canSubmit)
        v.setReason("contaminated")
        v.openChoosePacks(); v.togglePack(2); v.finishChoosePacks()
        v.submitDialog()
        assertEquals("contaminated", v.pending.value!!.reason)
        v.confirm(); advanceUntilIdle()
        assertEquals(listOf<List<Int>?>(listOf(2)), stock.addPackIds)
    }

    @Test fun openChoosePacks_ignoredWithoutCountOrOnIntake() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("discard")
        v.openChoosePacks()
        assertFalse(v.dialog.value!!.choosing) // no count yet
        v.openDialog("intake"); v.setCount(2); v.openChoosePacks()
        assertFalse(v.dialog.value!!.choosing)
    }

    @Test fun openUseDialog_refreshesPreviewAfterAnotherDeviceWrote() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        // Another device used packs 1 and 2 meanwhile.
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 2, packs = packs.drop(2)).copy(balanceMl = 100, unknownMlPacks = 1)) }
        v.openDialog("use")
        assertEquals(listOf(1), v.dialog.value!!.previewPacks.map { it.id }) // stale until the fetch lands
        advanceUntilIdle()
        assertEquals(listOf(3), v.dialog.value!!.previewPacks.map { it.id })
        v.setCount(2)
        assertEquals(listOf(3, 4), v.dialog.value!!.previewPacks.map { it.id })
        assertEquals(listOf(3, 4), v.detail.value!!.packs.map { it.id })
    }

    @Test fun openIntakeDialog_doesNotRefetch() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        val before = patients.detailCalls
        v.openDialog("intake"); advanceUntilIdle()
        assertEquals(before, patients.detailCalls)
    }

    @Test fun conflictOnChosenPacks_showsMessageAndReloadsPacks() = runTest {
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(409, "고른 팩을 다시 확인해 주세요")) }
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(1)
        v.openChoosePacks(); v.togglePack(3); v.finishChoosePacks()
        v.submitDialog()
        assertEquals(listOf(3), v.dialog.value?.chosenPackIds ?: listOf(3))
        // Pack 3 left on another device.
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 3, packs = packs.filter { it.id != 3 })) }
        v.confirm(); advanceUntilIdle()
        assertEquals("고른 팩을 다시 확인해 주세요", v.error.value)
        assertEquals(listOf(1, 2, 4), v.detail.value!!.packs.map { it.id })
    }

    @Test fun conflictOnChosenPacks_clearsTheChoiceInTheOpenDialog() = runTest {
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(409, "고른 팩을 다시 확인해 주세요")) }
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.setCount(1)
        v.openChoosePacks(); v.togglePack(3); v.finishChoosePacks()
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 3, packs = packs.filter { it.id != 3 })) }
        // The window stays open behind the confirmation when the nurse confirms from the dialog flow.
        v.request("use", 1, null, null, packIds = listOf(3))
        v.confirm(); advanceUntilIdle()
        assertNull(v.dialog.value!!.chosenPackIds)
        assertEquals(listOf(1), v.dialog.value!!.previewPacks.map { it.id })
    }

    @Test fun reloadAfterWriteFailing_isReported() = runTest {
        stock.addHandler = { _, _, c, _, _ -> ApiResult.Ok(RecordResultDto(recordDto(100, count = c), 5)) }
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        patients.detailHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        v.openDialog("intake"); v.setCount(1); v.submitDialog(); v.confirm(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
    }

    @Test fun success_carriesMlToDetailAndBanner_andReloadsPacks() = runTest {
        stock.addHandler = { _, _, c, _, _ -> ApiResult.Ok(RecordResultDto(recordDto(100, count = c), 6, balanceMl = 420, unknownMlPacks = 2)) }
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 6, packs = packs + PackDto(5, 80, t) + PackDto(6, 80, t)).copy(balanceMl = 420, unknownMlPacks = 2)) }
        v.openDialog("intake"); v.setCount(2); v.submitDialog(); v.confirm(); advanceUntilIdle()
        assertEquals(420, v.detail.value!!.balanceMl)
        assertEquals(2, v.detail.value!!.unknownMlPacks)
        assertEquals(6, v.detail.value!!.packs.size) // new packs fetched after the write
        assertEquals(ResultBanner("DEMO-1", "intake", 2, 6, 420, 2), banner.last.value)
    }

    @Test fun cancel_updatesBannerMl() = runTest {
        stock.cancelHandler = { id -> ApiResult.Ok(RecordResultDto(recordDto(101, kind = "cancel", targetId = id), 2, balanceMl = 160, unknownMlPacks = 1)) }
        banner.post(ResultBanner("DEMO-1", "intake", 3, 8, 640, 0))
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.cancelRecord(50); advanceUntilIdle()
        assertEquals(ResultBanner("DEMO-1", "intake", 3, 2, 160, 1), banner.last.value)
    }

    @Test fun closeDialog_clears() = runTest {
        val v = vmWithPacks(); v.refresh(); advanceUntilIdle()
        v.openDialog("use"); v.closeDialog()
        assertNull(v.dialog.value)
    }

    @Test fun intake_success_updatesBalanceRecordsSavedAndBanner() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 8, listOf(recordDto(100, count = 3), recordDto(50, staffId = 1, createdAt = clock.now())))) } // what the server returns after the write
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
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 8, listOf(recordDto(100, count = 3), recordDto(50, staffId = 1, createdAt = clock.now())))) } // what the server returns after the write
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
        patients.detailHandler = { ApiResult.Ok(detailDto(7, "DEMO-1", 2, listOf(recordDto(101, kind = "cancel", targetId = 50), rec.copy(canceled = true)))) } // after the cancel
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
        var calls = 0
        patients.detailHandler = {
            if (calls++ == 0) { gate.await(); ApiResult.Ok(detailDto(7, "DEMO-1", 5, emptyList())) } // pre-write snapshot
            else ApiResult.Ok(detailDto(7, "DEMO-1", 8, listOf(recordDto(100, count = 3)))) // after the write
        }
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

    @Test fun canCancel_falseForDischargedPatient() = runTest {
        patients.detailHandler = { ApiResult.Ok(detailDto(it).copy(status = "discharged")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
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

    @Test fun httpConflictOnAdd_refetchesOnceNoUncertainMessage() = runTest {
        stock.addHandler = { _, _, _, _, _ -> ApiResult.Err(ApiError(409, "재고가 부족합니다 (현재 0팩)")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        val calls = patients.detailCalls
        v.request("use", 1, null, null); v.confirm(); advanceUntilIdle()
        assertEquals(calls + 1, patients.detailCalls) // the packs may have changed: look again
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
