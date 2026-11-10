package app.milkbank

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStore
import app.milkbank.api.AuthInterceptor
import app.milkbank.api.MilkbankApi
import app.milkbank.core.SystemClock
import app.milkbank.data.AuthRepository
import app.milkbank.data.HttpAuthRepository
import app.milkbank.data.HttpPatientRepository
import app.milkbank.data.HttpStaffRepository
import app.milkbank.data.HttpStockRepository
import app.milkbank.data.PatientRepository
import app.milkbank.data.StaffRepository
import app.milkbank.data.StockRepository
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfigStore
import app.milkbank.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val Context.serverDataStore by preferencesDataStore(name = "server")

/** The repositories bound to the current server config. Fakeable as a whole. */
class Repositories(
    val auth: AuthRepository,
    val patients: PatientRepository,
    val stock: StockRepository,
    val staff: StaffRepository,
)

/** Manual DI. Repositories are rebuilt whenever the registered server changes. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkError = appContext.getString(R.string.network_error)
    private val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val serverConfigStore = ServerConfigStore(appContext.serverDataStore)
    val session = SessionManager(SystemClock)

    private val _repositories = MutableStateFlow(build(api = null))
    val repositories: StateFlow<Repositories> = _repositories.asStateFlow()

    private var overridden = false

    init {
        scope.launch {
            serverConfigStore.config.collect { config ->
                if (overridden) return@collect
                val api = config?.let {
                    val client = HttpClientFactory.okHttp(it, listOf(AuthInterceptor(session)), debuggable)
                    HttpClientFactory.retrofit(it, client).create(MilkbankApi::class.java)
                }
                _repositories.value = build(api)
            }
        }
    }

    val auth: AuthRepository get() = _repositories.value.auth
    val patients: PatientRepository get() = _repositories.value.patients
    val stock: StockRepository get() = _repositories.value.stock
    val staff: StaffRepository get() = _repositories.value.staff

    /** Replaces the repositories with fakes and stops following config changes. */
    @VisibleForTesting
    fun overrideForTest(repositories: Repositories) {
        overridden = true
        _repositories.value = repositories
    }

    private fun build(api: MilkbankApi?) = Repositories(
        auth = HttpAuthRepository(api, session, networkError),
        patients = HttpPatientRepository(api, networkError),
        stock = HttpStockRepository(api, networkError),
        staff = HttpStaffRepository(api, networkError),
    )
}
