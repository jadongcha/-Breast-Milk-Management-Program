package app.milkbank.data

import app.milkbank.api.LoginRequest
import app.milkbank.api.LoginResponse
import app.milkbank.api.MilkbankApi
import app.milkbank.api.PasswordChangeRequest
import app.milkbank.api.PatientDetailDto
import app.milkbank.api.PatientDto
import app.milkbank.api.PatientForm
import app.milkbank.api.RecordRequest
import app.milkbank.api.RecordResultDto
import app.milkbank.api.StaffCreateRequest
import app.milkbank.api.StaffCreateResponse
import app.milkbank.api.StaffDto
import app.milkbank.api.StaffPatchRequest
import app.milkbank.api.TempPasswordResponse
import app.milkbank.api.apiCall
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager

interface AuthRepository {
    suspend fun login(username: String, password: String): ApiResult<LoginResponse>
    suspend fun logout(): ApiResult<Unit>
    suspend fun changePassword(current: String, new: String): ApiResult<Unit>

    /** Pre-registration check against [config]; Ok on HTTP 200. */
    suspend fun health(config: ServerConfig): ApiResult<Unit>
}

interface PatientRepository {
    suspend fun list(q: String?): ApiResult<List<PatientDto>>
    suspend fun detail(id: Int): ApiResult<PatientDetailDto>
    suspend fun create(form: PatientForm): ApiResult<PatientDto>
    suspend fun update(id: Int, form: PatientForm): ApiResult<PatientDto>
}

interface StockRepository {
    /** kind is "intake", "use" or "discard". */
    suspend fun add(patientId: Int, kind: String, count: Int, reason: String?, memo: String?): ApiResult<RecordResultDto>
    suspend fun cancel(recordId: Int): ApiResult<RecordResultDto>
}

interface StaffRepository {
    suspend fun list(): ApiResult<List<StaffDto>>
    suspend fun create(username: String, name: String, role: String): ApiResult<StaffCreateResponse>
    suspend fun setActive(id: Int, active: Boolean): ApiResult<StaffDto>
    suspend fun resetPassword(id: Int): ApiResult<TempPasswordResponse>
}

/** Runs [block] against [api], or returns the network error when no server is registered. */
private suspend fun <T> MilkbankApi?.call(
    msg: String,
    block: suspend (MilkbankApi) -> T,
): ApiResult<T> {
    val a = this ?: return ApiResult.Err(ApiError(null, msg))
    return apiCall(msg) { block(a) }
}

class HttpAuthRepository(
    private val api: MilkbankApi?,
    private val session: SessionManager,
    private val networkError: String,
    private val healthApiFactory: (ServerConfig) -> MilkbankApi = ::pinnedApi,
) : AuthRepository {
    override suspend fun login(username: String, password: String): ApiResult<LoginResponse> {
        val r = api.call(networkError) { it.login(LoginRequest(username, password)) }
        if (r is ApiResult.Ok) session.start(r.value.token, r.value.staff)
        return r
    }

    override suspend fun logout(): ApiResult<Unit> {
        val r = api.call(networkError) { it.logout() }
        session.end(LogoutReason.USER) // always leave, even if the server was unreachable
        return r
    }

    override suspend fun changePassword(current: String, new: String): ApiResult<Unit> =
        api.call(networkError) { it.changePassword(PasswordChangeRequest(current, new)) }

    override suspend fun health(config: ServerConfig): ApiResult<Unit> {
        val temp = healthApiFactory(config)
        return apiCall(networkError) { temp.health() }
    }
}

private fun pinnedApi(config: ServerConfig): MilkbankApi =
    HttpClientFactory.retrofit(config, HttpClientFactory.okHttp(config)).create(MilkbankApi::class.java)

class HttpPatientRepository(private val api: MilkbankApi?, private val networkError: String) : PatientRepository {
    override suspend fun list(q: String?) = api.call(networkError) { it.patients(q?.takeIf { s -> s.isNotBlank() }) }
    override suspend fun detail(id: Int) = api.call(networkError) { it.patient(id) }
    override suspend fun create(form: PatientForm) = api.call(networkError) { it.createPatient(form) }
    override suspend fun update(id: Int, form: PatientForm) = api.call(networkError) { it.updatePatient(id, form) }
}

class HttpStockRepository(private val api: MilkbankApi?, private val networkError: String) : StockRepository {
    override suspend fun add(patientId: Int, kind: String, count: Int, reason: String?, memo: String?) =
        api.call(networkError) { it.addRecord(patientId, RecordRequest(kind, count, reason, memo)) }

    override suspend fun cancel(recordId: Int) = api.call(networkError) { it.cancelRecord(recordId) }
}

class HttpStaffRepository(private val api: MilkbankApi?, private val networkError: String) : StaffRepository {
    override suspend fun list() = api.call(networkError) { it.staff() }
    override suspend fun create(username: String, name: String, role: String) =
        api.call(networkError) { it.createStaff(StaffCreateRequest(username, name, role)) }

    override suspend fun setActive(id: Int, active: Boolean) =
        api.call(networkError) { it.setActive(id, StaffPatchRequest(active)) }

    override suspend fun resetPassword(id: Int) = api.call(networkError) { it.resetPassword(id) }
}
