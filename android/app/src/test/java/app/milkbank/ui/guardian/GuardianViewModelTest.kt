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
        val logouts = mutableListOf<Pair<String, String>>()
        override suspend fun login(stationToken: String, phone: String, babyId: String) = loginHandler()
        override suspend fun babies(stationToken: String, guardianToken: String) = babiesHandler()
        override suspend fun submit(
            stationToken: String,
            guardianToken: String,
            patientId: Int,
            count: Int,
        ): ApiResult<GuardianSubmitResponse> {
            submitCalls++
            return submitHandler()
        }

        override fun logout(stationToken: String, guardianToken: String): Job {
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
        { repo }, { stationToken }, idle, { invalidCalls++ }, "확인 못함", "오류", {},
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
        assertEquals(start, vm.step.value)
        assertEquals(0, invalidCalls)
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

    @Test fun pickCount_outOfRange_ignored() {
        val vm = vm()
        login(vm)
        vm.startSubmit()
        vm.pickCount(11)
        assertEquals(GuardianStep.PickCount(b1, null), vm.step.value)
    }
}
