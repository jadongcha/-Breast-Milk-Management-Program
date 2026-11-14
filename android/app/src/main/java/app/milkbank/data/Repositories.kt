package app.milkbank.data

import app.milkbank.api.DailyDto
import app.milkbank.api.DischargeRequest
import app.milkbank.api.LoginRequest
import app.milkbank.api.LoginResponse
import app.milkbank.api.MilkbankApi
import app.milkbank.api.OverviewDto
import app.milkbank.api.PasswordChangeRequest
import app.milkbank.api.PatientDetailDto
import app.milkbank.api.PatientDto
import app.milkbank.api.PatientForm
import app.milkbank.api.RecordRequest
import app.milkbank.api.RecordResultDto
import app.milkbank.api.SeriesPointDto
import app.milkbank.api.SettingsDto
import app.milkbank.api.SettingsPatch
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

interface AuthRepository {
    suspend fun login(username: String, password: String): ApiResult<LoginResponse>
    /**
     * Ends the local session at once, then tells the server in the background (best effort).
     * The returned job completes when the server call is done; callers may ignore it.
     */
    fun logout(): Job
    suspend fun changePassword(current: String, new: String): ApiResult<Unit>

    /** Pre-registration check against [config]; Ok on HTTP 200. */
    suspend fun health(config: ServerConfig): ApiResult<Unit>

    /** Connectivity check through the current pinned client (GET /health); false when unreachable. */
    suspend fun ping(): Boolean
}

interface PatientRepository {
    /** [includeDischarged] is for the admin list; nurses never set it. */
    suspend fun list(q: String?, includeDischarged: Boolean = false): ApiResult<List<PatientDto>>
    suspend fun detail(id: Int): ApiResult<PatientDetailDto>
    suspend fun create(form: PatientForm): ApiResult<PatientDto>
    suspend fun update(id: Int, form: PatientForm): ApiResult<PatientDto>
}

interface StockRepository {
    /** kind is "intake", "use" or "discard". */
    suspend fun add(patientId: Int, kind: String, count: Int, reason: String?, memo: String?, ml: Int?): ApiResult<RecordResultDto>
    suspend fun cancel(recordId: Int): ApiResult<RecordResultDto>
}

interface StaffRepository {
    suspend fun list(): ApiResult<List<StaffDto>>
    suspend fun create(username: String, name: String, role: String): ApiResult<StaffCreateResponse>
    suspend fun setActive(id: Int, active: Boolean): ApiResult<StaffDto>
    suspend fun resetPassword(id: Int): ApiResult<TempPasswordResponse>
}

/** Runs [block] against [api], or returns the network error when no server is registered. */
internal suspend fun <T> MilkbankApi?.call(
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
    private val healthApiFactory: ((ServerConfig) -> MilkbankApi)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val cleanup: (OkHttpClient?) -> Unit = { it?.shutdownQuietly() },
) : AuthRepository {
    override suspend fun login(username: String, password: String): ApiResult<LoginResponse> {
        val r = api.call(networkError) { it.login(LoginRequest(username, password)) }
        if (r is ApiResult.Ok) session.start(r.value.token, r.value.staff, r.value.staffIdleMinutes)
        return r
    }

    override fun logout(): Job {
        val token = session.state.value?.token
        session.end(LogoutReason.USER) // leave at once: the UI must not wait for the network
        val a = api
        if (token == null || a == null) return Job().apply { complete() }
        // App-scoped (outlives the screen/ViewModel); the token is sent explicitly so session state is irrelevant.
        return appScope.launch(ioDispatcher) {
            try {
                a.logout("Bearer $token")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort: the server drops idle tokens anyway
            }
        }
    }

    override suspend fun changePassword(current: String, new: String): ApiResult<Unit> =
        api.call(networkError) { it.changePassword(PasswordChangeRequest(current, new)) }

    override suspend fun health(config: ServerConfig): ApiResult<Unit> {
        var client: OkHttpClient? = null
        val temp = if (healthApiFactory == null) {
            pinnedApi(config).also { client = it.second }.first
        } else {
            healthApiFactory.invoke(config)
        }
        try {
            return apiCall(networkError) { temp.health() }
        } finally {
            // Closing pooled TLS sockets writes to the network: never on the caller's (Main) thread,
            // and not skippable by cancellation.
            withContext(NonCancellable + ioDispatcher) { cleanup(client) }
        }
    }

    override suspend fun ping(): Boolean = api.call(networkError) { it.health() } is ApiResult.Ok
}

/** Stops the client's executor and closes pooled sockets. Blocking I/O: call off the main thread. */
fun OkHttpClient.shutdownQuietly() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
}

private fun pinnedApi(config: ServerConfig): Pair<MilkbankApi, OkHttpClient> {
    val client = HttpClientFactory.okHttp(config)
    return HttpClientFactory.retrofit(config, client).create(MilkbankApi::class.java) to client
}

class HttpPatientRepository(private val api: MilkbankApi?, private val networkError: String) : PatientRepository {
    override suspend fun list(q: String?, includeDischarged: Boolean) =
        api.call(networkError) { it.patients(q?.takeIf { s -> s.isNotBlank() }, includeDischarged.takeIf { b -> b }) }
    override suspend fun detail(id: Int) = api.call(networkError) { it.patient(id) }
    override suspend fun create(form: PatientForm) = api.call(networkError) { it.createPatient(form) }
    override suspend fun update(id: Int, form: PatientForm) = api.call(networkError) { it.updatePatient(id, form) }
}

class HttpStockRepository(private val api: MilkbankApi?, private val networkError: String) : StockRepository {
    override suspend fun add(patientId: Int, kind: String, count: Int, reason: String?, memo: String?, ml: Int?) =
        api.call(networkError) { it.addRecord(patientId, RecordRequest(kind, count, reason, memo, ml)) }

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

interface AdminRepository {
    suspend fun overview(): ApiResult<OverviewDto>
    suspend fun daily(date: String?): ApiResult<DailyDto>
    suspend fun series(days: Int, patientId: Int?): ApiResult<List<SeriesPointDto>>
    suspend fun exportCsv(from: String, to: String): ApiResult<ByteArray>
    suspend fun settings(): ApiResult<SettingsDto>
    suspend fun saveSettings(patch: SettingsPatch): ApiResult<SettingsDto>
    suspend fun discharge(id: Int, remaining: String, discardReason: String?): ApiResult<PatientDto>
    suspend fun erase(id: Int): ApiResult<PatientDto>
}

class HttpAdminRepository(private val api: MilkbankApi?, private val networkError: String) : AdminRepository {
    override suspend fun overview() = api.call(networkError) { it.overview() }
    override suspend fun daily(date: String?) = api.call(networkError) { it.daily(date) }
    override suspend fun series(days: Int, patientId: Int?) = api.call(networkError) { it.series(days, patientId) }
    override suspend fun exportCsv(from: String, to: String) =
        api.call(networkError) { it.exportCsv(from, to).use { body -> body.bytes() } }

    override suspend fun settings() = api.call(networkError) { it.settings() }
    override suspend fun saveSettings(patch: SettingsPatch) = api.call(networkError) { it.saveSettings(patch) }
    override suspend fun discharge(id: Int, remaining: String, discardReason: String?) =
        api.call(networkError) { it.discharge(id, DischargeRequest(remaining, discardReason)) }

    override suspend fun erase(id: Int) = api.call(networkError) { it.erase(id) }
}
