package app.milkbank

import app.milkbank.api.BabyDto
import app.milkbank.api.ConfirmResponse
import app.milkbank.api.GuardianLoginResponse
import app.milkbank.api.GuardianSubmitResponse
import app.milkbank.api.PendingDto
import app.milkbank.api.RejectResponse
import app.milkbank.api.StationCreateResponse
import app.milkbank.api.StationDto
import app.milkbank.api.StationSelfDto
import app.milkbank.api.SubmissionBriefDto
import app.milkbank.data.GuardianRepository
import app.milkbank.data.StationRepository
import app.milkbank.data.SubmissionRepository
import kotlinx.coroutines.Job
import app.milkbank.api.LoginResponse
import app.milkbank.api.PatientDetailDto
import app.milkbank.api.PatientDto
import app.milkbank.api.PatientForm
import app.milkbank.api.RecordDto
import app.milkbank.api.RecordResultDto
import app.milkbank.api.StaffCreateResponse
import app.milkbank.api.StaffDto
import app.milkbank.api.TempPasswordResponse
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.data.AuthRepository
import app.milkbank.data.PatientRepository
import app.milkbank.data.StaffRepository
import app.milkbank.data.StockRepository
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import java.time.Instant

const val FAKE_BABY_NAME = "가짜아기이름"
const val FAKE_REG_NO = "DEMO-10"
const val FAKE_STATION_NAME = "Station 1"
const val FAKE_GUARDIAN_PHONE = "01012345678"
const val FAKE_USERNAME = "nurse1"
const val FAKE_PASSWORD = "test-pass-1"

val fakeNurse = StaffDto(
    id = 1, username = FAKE_USERNAME, name = "김간호", role = "nurse", active = true, mustChangePassword = false,
)

/** One patient whose balance the fake stock repository updates; shared by the fake repositories. */
class FakeWorld {
    @Volatile var balance = 0
    @Volatile var pending = 0
    val records = mutableListOf<RecordDto>()

    fun patient() = PatientDto(
        id = 10, regNo = FAKE_REG_NO, babyName = FAKE_BABY_NAME, bed = "3", guardianName = "보호자",
        guardianPhone = "010-0000-0000", status = "admitted", admittedOn = "2026-11-01",
        alertEnabled = false, balance = balance,
    )

    fun detail() = PatientDetailDto(
        id = 10, regNo = FAKE_REG_NO, babyName = FAKE_BABY_NAME, bed = "3", guardianName = "보호자",
        guardianPhone = "010-0000-0000", status = "admitted", admittedOn = "2026-11-01",
        alertEnabled = false, balance = balance, records = records.toList().reversed(),
    )
}

class FakeAuth(private val session: SessionManager) : AuthRepository {
    override suspend fun login(username: String, password: String): ApiResult<LoginResponse> =
        if (username == FAKE_USERNAME && password == FAKE_PASSWORD) {
            ApiResult.Ok(LoginResponse("fake-token", false, fakeNurse))
        } else {
            ApiResult.Err(ApiError(401, "아이디 또는 비밀번호가 올바르지 않습니다"))
        }

    override fun logout(): kotlinx.coroutines.Job {
        session.end(LogoutReason.USER)
        return kotlinx.coroutines.Job().apply { complete() }
    }

    override suspend fun changePassword(current: String, new: String): ApiResult<Unit> = ApiResult.Ok(Unit)
    override suspend fun health(config: ServerConfig): ApiResult<Unit> = ApiResult.Ok(Unit)
    override suspend fun ping(): Boolean = true
}

class FakePatients(private val world: FakeWorld) : PatientRepository {
    override suspend fun list(q: String?): ApiResult<List<PatientDto>> {
        val p = world.patient()
        val hit = q.isNullOrBlank() || p.regNo.contains(q.trim(), ignoreCase = true)
        return ApiResult.Ok(if (hit) listOf(p) else emptyList())
    }

    override suspend fun detail(id: Int): ApiResult<PatientDetailDto> = ApiResult.Ok(world.detail())
    override suspend fun create(form: PatientForm): ApiResult<PatientDto> = ApiResult.Ok(world.patient())
    override suspend fun update(id: Int, form: PatientForm): ApiResult<PatientDto> = ApiResult.Ok(world.patient())
}

class FakeStock(private val world: FakeWorld) : StockRepository {
    override suspend fun add(
        patientId: Int, kind: String, count: Int, reason: String?, memo: String?,
    ): ApiResult<RecordResultDto> {
        val serverKind = if (kind == "intake") "in" else kind
        world.balance += if (kind == "intake") count else -count
        val record = RecordDto(
            id = world.records.size + 1, kind = serverKind, count = count, staffId = fakeNurse.id,
            staffName = fakeNurse.name, createdAt = Instant.now(), canceled = false,
        )
        world.records += record
        return ApiResult.Ok(RecordResultDto(record, world.balance))
    }

    override suspend fun cancel(recordId: Int): ApiResult<RecordResultDto> =
        ApiResult.Err(ApiError(null, "unsupported in fake"))
}

class FakeStaff : StaffRepository {
    override suspend fun list(): ApiResult<List<StaffDto>> = ApiResult.Ok(listOf(fakeNurse))
    override suspend fun create(username: String, name: String, role: String): ApiResult<StaffCreateResponse> =
        ApiResult.Err(ApiError(null, "unsupported in fake"))

    override suspend fun setActive(id: Int, active: Boolean): ApiResult<StaffDto> = ApiResult.Ok(fakeNurse)
    override suspend fun resetPassword(id: Int): ApiResult<TempPasswordResponse> =
        ApiResult.Err(ApiError(null, "unsupported in fake"))
}

private val unsupported = ApiError(null, "unsupported in fake")

class FakeStations : StationRepository {
    override suspend fun create(name: String): ApiResult<StationCreateResponse> = ApiResult.Err(unsupported)
    override suspend fun list(): ApiResult<List<StationDto>> = ApiResult.Ok(emptyList())
    override suspend fun deactivate(id: Int): ApiResult<StationDto> = ApiResult.Err(unsupported)
    override suspend fun self(stationToken: String): ApiResult<StationSelfDto> =
        ApiResult.Ok(StationSelfDto(1, FAKE_STATION_NAME))
    override suspend fun releaseSelf(stationToken: String): ApiResult<Unit> = ApiResult.Ok(Unit)
}

class FakeGuardian(private val world: FakeWorld? = null) : GuardianRepository {
    private fun baby() = BabyDto(10, FAKE_REG_NO, world?.balance ?: 0, world?.pending ?: 0)

    override suspend fun login(stationToken: String, phone: String, babyId: String):
        ApiResult<GuardianLoginResponse> =
        if (world != null && phone == FAKE_GUARDIAN_PHONE && babyId == FAKE_REG_NO) {
            ApiResult.Ok(GuardianLoginResponse("fake-guardian-token", listOf(baby())))
        } else {
            ApiResult.Err(unsupported)
        }

    override suspend fun babies(stationToken: String, guardianToken: String): ApiResult<List<BabyDto>> =
        if (world != null) ApiResult.Ok(listOf(baby())) else ApiResult.Err(unsupported)

    override suspend fun submit(stationToken: String, guardianToken: String, patientId: Int, count: Int):
        ApiResult<GuardianSubmitResponse> {
        val w = world ?: return ApiResult.Err(unsupported)
        w.pending += count
        return ApiResult.Ok(
            GuardianSubmitResponse(SubmissionBriefDto(1, count, Instant.now()), w.balance, w.pending),
        )
    }

    override fun logout(stationToken: String, guardianToken: String): Job = Job().apply { complete() }
}

class FakeSubmissions : SubmissionRepository {
    override suspend fun pending(): ApiResult<List<PendingDto>> = ApiResult.Ok(emptyList())
    override suspend fun confirm(id: Int, count: Int): ApiResult<ConfirmResponse> = ApiResult.Err(unsupported)
    override suspend fun reject(id: Int, reason: String, memo: String?): ApiResult<RejectResponse> =
        ApiResult.Err(unsupported)
}

fun fakeRepositories(session: SessionManager, world: FakeWorld) = Repositories(
    auth = FakeAuth(session),
    patients = FakePatients(world),
    stock = FakeStock(world),
    staff = FakeStaff(),
    stations = FakeStations(),
    guardian = FakeGuardian(world),
    submissions = FakeSubmissions(),
)
