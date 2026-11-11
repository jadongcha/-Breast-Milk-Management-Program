package app.milkbank

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStore
import app.milkbank.api.AuthInterceptor
import app.milkbank.api.MilkbankApi
import app.milkbank.core.ElapsedClock
import app.milkbank.core.SystemClock
import app.milkbank.data.AuthRepository
import app.milkbank.data.HttpAuthRepository
import app.milkbank.data.HttpPatientRepository
import app.milkbank.data.HttpStaffRepository
import app.milkbank.data.HttpStockRepository
import app.milkbank.data.PatientRepository
import app.milkbank.data.StaffRepository
import app.milkbank.data.StockRepository
import app.milkbank.data.GuardianRepository
import app.milkbank.data.HttpGuardianRepository
import app.milkbank.data.HttpStationRepository
import app.milkbank.data.HttpSubmissionRepository
import app.milkbank.data.StationRepository
import app.milkbank.data.SubmissionRepository
import app.milkbank.data.shutdownQuietly
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfig
import app.milkbank.server.ServerConfigStore
import app.milkbank.server.shouldResetSession
import app.milkbank.session.ConnectionMonitor
import app.milkbank.session.IdleWatcher
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import app.milkbank.station.KeystoreTokenCipher
import app.milkbank.station.StationStore
import app.milkbank.ui.patients.ResultBannerStore
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.serverDataStore by preferencesDataStore(name = "server")
private val Context.stationDataStore by preferencesDataStore(name = "station")

/** The repositories bound to the current server config. Fakeable as a whole. */
class Repositories(
    val auth: AuthRepository,
    val patients: PatientRepository,
    val stock: StockRepository,
    val staff: StaffRepository,
    val stations: StationRepository,
    val guardian: GuardianRepository,
    val submissions: SubmissionRepository,
)

/** Manual DI. Repositories are rebuilt whenever the registered server changes. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkError = appContext.getString(R.string.network_error)
    private val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val serverConfigStore = ServerConfigStore(appContext.serverDataStore)
    val stationStore = StationStore(appContext.stationDataStore, KeystoreTokenCipher())
    val session = SessionManager(SystemClock)
    val idleWatcher = IdleWatcher(ElapsedClock, session)

    /** Last saved stock result, shared by the detail and home screens; dropped when the session ends. */
    val resultBanner = ResultBannerStore()
    /**
     * True once the server said this tablet is not a registered station (station_invalid). The guardian
     * home then shows the "invalid" screen. Reset when the station info is cleared.
     */
    val stationInvalid = MutableStateFlow(false)
    private val _serverKey = MutableStateFlow<Any?>(null)
    val connection = ConnectionMonitor(scope, serverKey = _serverKey) { auth.ping() }

    private val _repositories = MutableStateFlow(build(api = null))
    val repositories: StateFlow<Repositories> = _repositories.asStateFlow()

    private var overridden = false
    private var previousConfig: ServerConfig? = null
    private var currentClient: OkHttpClient? = null

    init {
        scope.launch { session.state.collect { if (it == null) resultBanner.clear() } }
        scope.launch { stationStore.config.collect { if (it == null) stationInvalid.value = false } }
        scope.launch {
            serverConfigStore.config.collect { config ->
                if (overridden) return@collect
                applyConfig(config)
            }
        }
    }

    private fun applyConfig(config: ServerConfig?) {
        if (shouldResetSession(previousConfig, config)) session.end(LogoutReason.USER)
        // A station token belongs to one server: drop it when the server changes or is cleared.
        if (config == null || shouldResetSession(previousConfig, config)) scope.launch { stationStore.clear() }
        val oldClient = currentClient
        val client = config?.let {
            HttpClientFactory.okHttp(it, listOf(AuthInterceptor(session)), debuggable)
        }
        val api = config?.let { HttpClientFactory.retrofit(it, client!!).create(MilkbankApi::class.java) }
        currentClient = client
        previousConfig = config
        _repositories.value = build(api)
        // Non-null only once an api exists; a change makes the monitor probe again at once.
        _serverKey.value = if (config != null) _repositories.value else null
        // Closing pooled sockets is blocking network I/O: keep it off whichever thread applied the config.
        oldClient?.let { scope.launch(Dispatchers.IO) { it.shutdownQuietly() } }
    }

    val auth: AuthRepository get() = _repositories.value.auth
    val patients: PatientRepository get() = _repositories.value.patients
    val stock: StockRepository get() = _repositories.value.stock
    val staff: StaffRepository get() = _repositories.value.staff
    val stations: StationRepository get() = _repositories.value.stations
    val guardian: GuardianRepository get() = _repositories.value.guardian
    val submissions: SubmissionRepository get() = _repositories.value.submissions

    /** Replaces the repositories with fakes and stops following config changes. */
    @VisibleForTesting
    fun overrideForTest(repositories: Repositories) {
        overridden = true
        _repositories.value = repositories
        _serverKey.value = repositories
    }

    /** Undoes [overrideForTest]: rebuilds the real repositories from the stored config and follows it again. */
    @VisibleForTesting
    fun clearTestOverride() {
        overridden = false
        scope.launch { applyConfig(serverConfigStore.config.first()) }
    }

    private fun build(api: MilkbankApi?) = Repositories(
        auth = HttpAuthRepository(api, session, networkError, appScope = scope),
        patients = HttpPatientRepository(api, networkError),
        stock = HttpStockRepository(api, networkError),
        staff = HttpStaffRepository(api, networkError),
        stations = HttpStationRepository(api, networkError),
        guardian = HttpGuardianRepository(api, networkError, appScope = scope),
        submissions = HttpSubmissionRepository(api, networkError),
    )
}
