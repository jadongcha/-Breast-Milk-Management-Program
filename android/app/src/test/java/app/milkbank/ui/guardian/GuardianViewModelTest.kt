package app.milkbank.ui.guardian

import app.milkbank.api.BabyDto
import app.milkbank.api.GuardianLoginResponse
import app.milkbank.api.GuardianSubmitResponse
import app.milkbank.api.SubmissionBriefDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.data.GuardianRepository
import app.milkbank.station.GuardianIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GuardianViewModelTest {
    private val b1 = BabyDto(11, "DEMO-01", 5, 0)
    private val b2 = BabyDto(12, "DEMO-02", 2, 1)

    private class FakeGuardian : GuardianRepository {
        var loginHandler: suspend () -> ApiResult<GuardianLoginResponse> = { error("unset") }
        var babiesHandler: suspend () -> ApiResult<List<BabyDto>> = { error("unset") }
        var submitHandler: suspend () -> ApiResult<GuardianSubmitResponse> = { error("unset") }
        var submitCalls = 0
        val logouts = mutableListOf<Pair<String?, String>>()
        override suspend fun login(stationToken: String?, phone: String, babyId: String) = loginHandler()
        override suspend fun babies(stationToken: String?, guardianToken: String) = babiesHandler()
        override suspend fun submit(
            stationToken: String?,
            guardianToken: String,
            patientId: Int,
            count: Int,
        ): ApiResult<GuardianSubmitResponse> {
            submitCalls++
            return submitHandler()
        }

        override fun logout(stationToken: String?, guardianToken: String): Job {
            logouts += stationToken to guardianToken
            return Job().apply { complete() }
        }
    }

    private val repo = FakeGuardian()
    private val clock = FakeClock(Instant.parse("2026-11-12T00:00:00Z"))
    private val idle = GuardianIdle(clock)
    private var invalidCalls = 0
    private var stationToken: String? = "st"
    private val start = GuardianStep.Start("", "", null, false)

    private fun vm() = GuardianViewModel(
        { repo }, { stationToken }, idle, { invalidCalls++ }, "확인 못함", "오류", "세션 끝", clock, {},
    )

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun login(vm: GuardianViewModel, babies: List<BabyDto> = listOf(b1)) {
        repo.loginHandler = { ApiResult.Ok(GuardianLoginResponse("gt", babies)) }
        vm.onPhone("01012345678")
        vm.onBabyId("DEMO-01")
        vm.login()
    }

    @Test fun login_singleBaby_goesToStock() {
        val vm = vm()
        login(vm)
        assertEquals(GuardianStep.Stock(b1), vm.step.value)
    }

    @Test fun login_twins_goesToSelect() {
        val vm = vm()
        login(vm, listOf(b1, b2))
        assertEquals(GuardianStep.SelectBaby(listOf(b1, b2)), vm.step.value)
        vm.selectBaby(b2)
        assertEquals(GuardianStep.Stock(b2), vm.step.value)
    }

    @Test fun login_failure_showsServerMessage_keepsInputs() {
        val vm = vm()
        repo.loginHandler = { ApiResult.Err(ApiError(401, "전화번호 또는 아기 ID가 올바르지 않습니다")) }
        vm.onPhone("010")
        vm.onBabyId("X")
        vm.login()
        assertEquals(
            GuardianStep.Start("010", "X", "전화번호 또는 아기 ID가 올바르지 않습니다", false),
            vm.step.value,
        )
    }

    @Test fun submit_success_showsDone() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        assertEquals(GuardianStep.ConfirmSubmit(b1, 3), vm.step.value)
        repo.submitHandler = { ApiResult.Ok(GuardianSubmitResponse(SubmissionBriefDto(1, 3, Instant.EPOCH), 2, 3)) }
        vm.submit()
        assertEquals(GuardianStep.Done(b1, 3, 2, 3), vm.step.value)
    }

    @Test fun submitWhileSubmitting_isIgnored() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        val gate = CompletableDeferred<Unit>()
        repo.submitHandler = {
            gate.await()
            ApiResult.Ok(GuardianSubmitResponse(SubmissionBriefDto(1, 3, Instant.EPOCH), 5, 3))
        }
        vm.submit()
        vm.submit()
        assertEquals(1, repo.submitCalls)
        gate.complete(Unit)
        assertTrue(vm.step.value is GuardianStep.Done)
        assertEquals(1, repo.submitCalls)
    }

    @Test fun submitNetworkError_refreshesAndWarns() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        repo.submitHandler = { ApiResult.Err(ApiError(null, "net")) }
        repo.babiesHandler = { ApiResult.Ok(listOf(b1.copy(pendingCount = 3))) }
        vm.submit()
        assertEquals(GuardianStep.Stock(b1.copy(pendingCount = 3), "확인 못함"), vm.step.value)
    }

    @Test fun idle60s_logsOutAndClearsEverything() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        assertEquals(start, vm.step.value)
        assertEquals(listOf("st" to "gt"), repo.logouts)
        assertFalse(vm.step.value.toString().contains("DEMO-01"))
    }

    @Test fun idleDuringSubmit_lateResultIsDropped() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        val gate = CompletableDeferred<Unit>()
        repo.submitHandler = {
            gate.await()
            ApiResult.Ok(GuardianSubmitResponse(SubmissionBriefDto(1, 3, Instant.EPOCH), 5, 3))
        }
        vm.submit()
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        gate.complete(Unit)
        assertEquals(start, vm.step.value)
    }

    @Test fun finish_logsOut() {
        val vm = vm()
        login(vm)
        vm.finish()
        assertEquals(start, vm.step.value)
        assertEquals(listOf("st" to "gt"), repo.logouts)
        clock.advance(Duration.ofMinutes(5))
        idle.check()
        assertEquals(1, repo.logouts.size) // disarmed
    }

    @Test fun stationInvalid_showsInvalidState() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(2)
        repo.submitHandler = { ApiResult.Err(ApiError(null, "net")) }
        repo.babiesHandler = { ApiResult.Err(ApiError(401, "x", "station_invalid")) }
        vm.submit()
        assertEquals(1, invalidCalls)
        assertEquals(start, vm.step.value)
    }

    @Test fun other401_onSubmit_endsSession() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(2)
        repo.submitHandler = { ApiResult.Err(ApiError(401, "만료")) }
        vm.submit()
        assertEquals(start.copy(error = "세션 끝"), vm.step.value)
        assertEquals(0, invalidCalls)
        vm.onPhone("010") // typing clears the notice
        assertEquals(null, (vm.step.value as GuardianStep.Start).error)
    }

    // The server's session timer counts from the last request, not the last touch.
    private val okSubmit = ApiResult.Ok(GuardianSubmitResponse(SubmissionBriefDto(1, 3, Instant.EPOCH), 2, 3))

    @Test fun submitAfter40sWithoutRequests_pingsBeforeSubmit() {
        val vm = vm()
        login(vm)
        val calls = mutableListOf<String>()
        repo.babiesHandler = { calls += "babies"; ApiResult.Ok(listOf(b1)) }
        repo.submitHandler = { calls += "submit"; okSubmit }
        clock.advance(Duration.ofSeconds(20)); idle.touch()
        vm.startSubmit() // 20 s: no ping yet
        assertEquals(emptyList<String>(), calls)
        clock.advance(Duration.ofSeconds(20)); idle.touch()
        vm.pickCount(3) // 40 s since login: ping
        assertEquals(listOf("babies"), calls)
        clock.advance(Duration.ofSeconds(5)); idle.touch()
        vm.submit() // 5 s since the ping: no second one
        assertEquals(listOf("babies", "submit"), calls)
        assertTrue(vm.step.value is GuardianStep.Done)
    }

    @Test fun submitStraightAfter40s_pingsOnceThenSubmits() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        val calls = mutableListOf<String>()
        repo.babiesHandler = { calls += "babies"; ApiResult.Ok(listOf(b1)) }
        repo.submitHandler = { calls += "submit"; okSubmit }
        clock.advance(Duration.ofSeconds(40)); idle.touch()
        vm.submit()
        assertEquals(listOf("babies", "submit"), calls)
    }

    @Test fun within30s_noExtraCall() {
        val vm = vm()
        login(vm)
        var babiesCalls = 0
        repo.babiesHandler = { babiesCalls++; ApiResult.Ok(listOf(b1)) }
        repo.submitHandler = { okSubmit }
        clock.advance(Duration.ofSeconds(29))
        vm.startSubmit()
        vm.pickCount(3)
        vm.submit()
        assertEquals(0, babiesCalls)
        assertEquals(1, repo.submitCalls)
    }

    @Test fun keepalive401_returnsToStartWithNotice_andNeverSubmits() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        repo.babiesHandler = { ApiResult.Err(ApiError(401, "만료")) }
        clock.advance(Duration.ofSeconds(40))
        vm.submit()
        assertEquals(start.copy(error = "세션 끝"), vm.step.value)
        assertEquals(0, repo.submitCalls)
        assertEquals(0, invalidCalls)
    }

    @Test fun keepaliveNetworkError_doesNotSubmit_showsStockWithMessage() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        repo.babiesHandler = { ApiResult.Err(ApiError(null, "net")) }
        clock.advance(Duration.ofSeconds(40))
        vm.submit()
        assertEquals(GuardianStep.Stock(b1, "오류"), vm.step.value)
        assertEquals(0, repo.submitCalls)
        assertFalse(vm.submitting.value)
    }

    @Test fun keepaliveStationInvalid_callsCallback() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        repo.babiesHandler = { ApiResult.Err(ApiError(401, "x", "station_invalid")) }
        clock.advance(Duration.ofSeconds(40))
        vm.submit()
        assertEquals(1, invalidCalls)
        assertEquals(start, vm.step.value)
        assertEquals(0, repo.submitCalls)
    }

    @Test fun typedInputsAreClearedByIdleToo() {
        val vm = vm()
        vm.onPhone("010")
        vm.onBabyId("DEMO-01")
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        assertEquals(start, vm.step.value)
        assertTrue(repo.logouts.isEmpty())
    }

    @Test fun login_stationInvalid_callsCallbackAndResets() {
        val vm = vm()
        repo.loginHandler = { ApiResult.Err(ApiError(401, "x", "station_invalid")) }
        vm.onPhone("010")
        vm.onBabyId("DEMO-01")
        vm.login()
        assertEquals(1, invalidCalls)
        assertEquals(start, vm.step.value)
    }

    @Test fun afterIdle_tokenIsGone_submitMakesNoCall() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(3)
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        vm.submit()
        assertEquals(0, repo.submitCalls)
    }

    @Test fun login_emptyBabies_logsOutServerToken() {
        val vm = vm()
        login(vm, emptyList())
        assertEquals(listOf("st" to "gt"), repo.logouts)
        assertEquals("오류", (vm.step.value as GuardianStep.Start).error)
    }

    @Test fun pickCount_outOfRange_ignored() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(11)
        assertEquals(GuardianStep.PickCount(b1, null), vm.step.value)
    }

    @Test fun general_idle_returnsToChoice_andClears() {
        stationToken = null
        val vm = vm()
        login(vm)
        assertEquals(0L, vm.sessionEnded.value)
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        assertEquals(1L, vm.sessionEnded.value)
        assertEquals(start, vm.step.value)
        assertEquals(listOf<Pair<String?, String>>(null to "gt"), repo.logouts)
    }

    @Test fun general_back_logsOutAndReturns() {
        stationToken = null
        val vm = vm()
        login(vm)
        vm.finish()
        assertEquals(listOf<Pair<String?, String>>(null to "gt"), repo.logouts)
        assertEquals(1L, vm.sessionEnded.value)
        assertEquals(start, vm.step.value)
    }

    @Test fun general_finish_beforeLogin_noServerCall() {
        stationToken = null
        val vm = vm()
        vm.onPhone("0101")
        vm.finish()
        assertTrue(repo.logouts.isEmpty())
        assertEquals(1L, vm.sessionEnded.value)
        assertEquals(start, vm.step.value)
    }

    @Test fun general_idle_whileTyping_beforeLogin_bumpsSessionEnded() {
        stationToken = null
        val vm = vm()
        vm.onPhone("0101")
        clock.advance(Duration.ofSeconds(60))
        idle.check()
        assertEquals(1L, vm.sessionEnded.value)
        assertTrue(repo.logouts.isEmpty())
    }

    @Test fun general_idle_noInputAtAll_endsSessionAfterArmIdle() {
        stationToken = null
        val vm = vm()
        vm.armIdle()
        clock.advance(Duration.ofSeconds(59))
        idle.check()
        assertEquals(0L, vm.sessionEnded.value)
        clock.advance(Duration.ofSeconds(1))
        idle.check()
        assertEquals(1L, vm.sessionEnded.value)
        assertEquals(start, vm.step.value)
        assertTrue(repo.logouts.isEmpty())
    }

    @Test fun general_other401_endsSessionAndBumps() {
        stationToken = null
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(2)
        repo.babiesHandler = { ApiResult.Err(ApiError(401, "x")) }
        clock.advance(Duration.ofSeconds(45))
        vm.submit()
        assertEquals(1L, vm.sessionEnded.value)
        assertEquals(0, invalidCalls)
    }
}
