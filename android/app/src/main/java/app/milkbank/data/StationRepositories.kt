package app.milkbank.data

import app.milkbank.api.BabyDto
import app.milkbank.api.ConfirmRequest
import app.milkbank.api.ConfirmResponse
import app.milkbank.api.GuardianLoginRequest
import app.milkbank.api.GuardianLoginResponse
import app.milkbank.api.GuardianSubmitRequest
import app.milkbank.api.GuardianSubmitResponse
import app.milkbank.api.MilkbankApi
import app.milkbank.api.PendingDto
import app.milkbank.api.RejectRequest
import app.milkbank.api.RejectResponse
import app.milkbank.api.StationCreateRequest
import app.milkbank.api.StationCreateResponse
import app.milkbank.api.StationDto
import app.milkbank.api.StationPatchRequest
import app.milkbank.api.StationSelfDto
import app.milkbank.core.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Staff-side management of submission stations, plus the tablet's own calls. */
interface StationRepository {
    suspend fun create(name: String): ApiResult<StationCreateResponse>
    suspend fun list(): ApiResult<List<StationDto>>
    suspend fun deactivate(id: Int): ApiResult<StationDto>
    suspend fun self(stationToken: String): ApiResult<StationSelfDto>

    /** Staff Bearer is attached by the interceptor. */
    suspend fun releaseSelf(stationToken: String): ApiResult<Unit>
}

/** Guardian calls. Both tokens are sent explicitly on every request; a null station token (general device) sends no header. */
interface GuardianRepository {
    suspend fun login(stationToken: String?, phone: String, babyId: String): ApiResult<GuardianLoginResponse>
    suspend fun babies(stationToken: String?, guardianToken: String): ApiResult<List<BabyDto>>
    suspend fun submit(
        stationToken: String?,
        guardianToken: String,
        patientId: Int,
        count: Int,
    ): ApiResult<GuardianSubmitResponse>

    /** Sent from the app scope without waiting for the answer; failures are ignored. */
    fun logout(stationToken: String?, guardianToken: String): Job
}

interface SubmissionRepository {
    suspend fun pending(): ApiResult<List<PendingDto>>
    suspend fun confirm(id: Int, count: Int, ml: Int?): ApiResult<ConfirmResponse>
    suspend fun reject(id: Int, reason: String, memo: String?): ApiResult<RejectResponse>
}

class HttpStationRepository(private val api: MilkbankApi?, private val networkError: String) : StationRepository {
    override suspend fun create(name: String) = api.call(networkError) { it.createStation(StationCreateRequest(name)) }
    override suspend fun list() = api.call(networkError) { it.stations() }
    override suspend fun deactivate(id: Int) =
        api.call(networkError) { it.patchStation(id, StationPatchRequest(false)) }

    override suspend fun self(stationToken: String) = api.call(networkError) { it.stationSelf(stationToken) }
    override suspend fun releaseSelf(stationToken: String) =
        api.call(networkError) { it.releaseStation(stationToken) }
}

class HttpGuardianRepository(
    private val api: MilkbankApi?,
    private val networkError: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : GuardianRepository {
    override suspend fun login(stationToken: String?, phone: String, babyId: String) =
        api.call(networkError) { it.guardianLogin(stationToken, GuardianLoginRequest(phone, babyId)) }

    override suspend fun babies(stationToken: String?, guardianToken: String) =
        api.call(networkError) { it.guardianBabies(stationToken, "Bearer $guardianToken") }

    override suspend fun submit(stationToken: String?, guardianToken: String, patientId: Int, count: Int) =
        api.call(networkError) {
            it.guardianSubmit(stationToken, "Bearer $guardianToken", GuardianSubmitRequest(patientId, count))
        }

    override fun logout(stationToken: String?, guardianToken: String): Job {
        val a = api ?: return Job().apply { complete() }
        return appScope.launch(ioDispatcher) {
            try {
                a.guardianLogout(stationToken, "Bearer $guardianToken")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort: the server drops idle guardian tokens anyway
            }
        }
    }
}

class HttpSubmissionRepository(private val api: MilkbankApi?, private val networkError: String) :
    SubmissionRepository {
    override suspend fun pending() = api.call(networkError) { it.submissions("pending") }
    override suspend fun confirm(id: Int, count: Int, ml: Int?) =
        api.call(networkError) { it.confirmSubmission(id, ConfirmRequest(count, ml)) }

    override suspend fun reject(id: Int, reason: String, memo: String?) =
        api.call(networkError) { it.rejectSubmission(id, RejectRequest(reason, memo)) }
}
