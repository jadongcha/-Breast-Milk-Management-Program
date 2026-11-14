package app.milkbank.ui.station

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.milkbank.api.LoginResponse
import app.milkbank.api.StaffDto
import app.milkbank.api.StationCreateResponse
import app.milkbank.api.StationDto
import app.milkbank.api.StationSelfDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.core.FakeClock
import app.milkbank.data.AuthRepository
import app.milkbank.data.StationRepository
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import app.milkbank.station.GuardianIdle
import app.milkbank.station.StationConfig
import app.milkbank.station.StationStore
import app.milkbank.station.TokenCipher
import app.milkbank.ui.login.FakeAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StationReleaseViewModelTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(cipher: String) = cipher.removePrefix("enc:")
    }

    private class FakeStations : StationRepository {
        var releaseCalls = 0
        var releaseHandler: suspend (String) -> ApiResult<Unit> = { ApiResult.Ok(Unit) }
        override suspend fun create(name: String): ApiResult<StationCreateResponse> = error("unused")
        override suspend fun list(): ApiResult<List<StationDto>> = error("unused")
        override suspend fun deactivate(id: Int): ApiResult<StationDto> = error("unused")
        override suspend fun self(stationToken: String): ApiResult<StationSelfDto> = error("unused")
        override suspend fun releaseSelf(stationToken: String): ApiResult<Unit> {
            releaseCalls++; return releaseHandler(stationToken)
        }
    }

    private val stations = FakeStations()
    private val fakeAuth = FakeAuthRepository()
    private val session = SessionManager(object : Clock { override fun now(): Instant = Instant.EPOCH })
    private lateinit var store: StationStore
    private val idleClock = FakeClock(Instant.parse("2026-11-12T00:00:00Z"))
    private val idle = GuardianIdle(idleClock)

    // Like the real repository: logout ends the local session at once.
    private val auth: AuthRepository = object : AuthRepository by fakeAuth {
        override fun logout() = fakeAuth.logout().also { session.end(LogoutReason.USER) }
    }

    private val admin = StaffDto(1, "admin", "관리자", "admin", true, false)
    private val nurse = StaffDto(2, "nurse1", "김간호", "nurse", true, false)
    private val station = StationConfig("tok-1", "신생아실 A")

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val ds: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { tempFolder.newFile("r.preferences_pb") })
        store = StationStore(ds, FakeCipher())
        store.save(station)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private fun vm() = StationReleaseViewModel(
        { auth }, { stations }, store, session, idle,
        adminOnlyMessage = "관리자만 해제할 수 있습니다",
        offlineMessage = "서버에 연결된 뒤 다시 시도해 주세요",
        unexpectedError = "서버에 연결할 수 없습니다",
    )

    private suspend fun StationReleaseViewModel.awaitSettled() =
        withTimeout(5000) { state.first { it !is ReleaseState.Releasing && !(it is ReleaseState.Login && it.loading) } }

    private fun loginAs(staff: StaffDto) {
        fakeAuth.loginHandler = { _, _ -> ApiResult.Ok(LoginResponse("tok", false, staff)) }
    }

    private suspend fun readyVm(): StationReleaseViewModel {
        loginAs(admin)
        return vm().also { it.login("admin", "pw"); it.awaitSettled() }
    }

    @Test fun release_nonAdminRejected() = runBlocking {
        loginAs(nurse)
        val vm = vm()
        vm.login("nurse1", "pw")
        assertEquals(ReleaseState.Login("관리자만 해제할 수 있습니다"), vm.awaitSettled())
        assertEquals(1, fakeAuth.logoutCalls)
        assertNull(session.state.value)
        assertEquals(station, store.config.first())
    }

    @Test fun login_admin_keepsTheServerIdleMinutes() = runBlocking {
        fakeAuth.loginHandler = { _, _ -> ApiResult.Ok(LoginResponse("tok", false, admin, staffIdleMinutes = 3)) }
        val vm = vm()
        vm.login("admin", "pw")
        assertEquals(ReleaseState.Ready, vm.awaitSettled())
        assertEquals(3, session.state.value?.idleMinutes)
    }

    @Test fun login_admin_becomesReady() = runBlocking {
        loginAs(admin)
        val vm = vm()
        vm.login("admin", "pw")
        assertEquals(ReleaseState.Ready, vm.awaitSettled())
        assertNotNull(session.state.value)
    }

    @Test fun login_failureShowsServerMessage() = runBlocking {
        fakeAuth.loginHandler = { _, _ -> ApiResult.Err(ApiError(401, "아이디 또는 비밀번호가 올바르지 않습니다")) }
        val vm = vm()
        vm.login("admin", "bad")
        assertEquals(ReleaseState.Login("아이디 또는 비밀번호가 올바르지 않습니다"), vm.awaitSettled())
        assertNull(session.state.value)
    }

    @Test fun release_ok_clearsAndEnds() = runBlocking {
        val vm = readyVm()
        vm.release("tok-1", stationInvalid = false)
        assertEquals(ReleaseState.Done, vm.awaitSettled())
        assertEquals(1, stations.releaseCalls)
        assertNull(store.config.first())
        assertNull(session.state.value)
        assertNull(session.logoutReason.value)
    }

    @Test fun release_station401_countsAsReleased() = runBlocking {
        val vm = readyVm()
        stations.releaseHandler = {
            // The interceptor already ended the staff session (EXPIRED) because of the 401.
            session.end(LogoutReason.EXPIRED)
            ApiResult.Err(ApiError(401, "x", "station_invalid"))
        }
        vm.release("tok-1", stationInvalid = false)
        assertEquals(ReleaseState.Done, vm.awaitSettled())
        assertNull(store.config.first())
        assertNull(session.logoutReason.value)
    }

    @Test fun release_offline_keepsStation() = runBlocking {
        val vm = readyVm()
        stations.releaseHandler = { ApiResult.Err(ApiError(null, "net")) }
        vm.release("tok-1", stationInvalid = false)
        assertEquals(ReleaseState.Error("서버에 연결된 뒤 다시 시도해 주세요"), vm.awaitSettled())
        assertEquals(station, store.config.first())
        assertNotNull(session.state.value)
    }

    @Test fun release_serverError_showsServerMessageKeepsStation() = runBlocking {
        val vm = readyVm()
        stations.releaseHandler = { ApiResult.Err(ApiError(500, "서버 오류")) }
        vm.release("tok-1", stationInvalid = false)
        assertEquals(ReleaseState.Error("서버 오류"), vm.awaitSettled())
        assertEquals(station, store.config.first())
    }

    @Test fun release_fromInvalidScreen_clearsEvenIfServerFails() = runBlocking {
        val vm = readyVm()
        stations.releaseHandler = { ApiResult.Err(ApiError(null, "net")) }
        vm.release("tok-1", stationInvalid = true)
        assertEquals(ReleaseState.Done, vm.awaitSettled())
        assertNull(store.config.first())
        assertNull(session.state.value)
    }

    @Test fun release_beforeLogin_isIgnored() = runBlocking {
        val vm = vm()
        vm.release("tok-1", stationInvalid = true)
        assertEquals(0, stations.releaseCalls)
        assertEquals(station, store.config.first())
    }

    @Test fun cancel_logsOutAdmin() = runBlocking {
        val vm = readyVm()
        vm.cancel()
        assertEquals(1, fakeAuth.logoutCalls)
        assertNull(session.state.value)
        assertEquals(station, store.config.first())
    }

    @Test fun cancel_withoutSession_doesNotLogout() {
        vm().cancel()
        assertEquals(0, fakeAuth.logoutCalls)
    }

    @Test fun sessionEndedWhileReady_backToLogin() = runBlocking {
        val vm = readyVm()
        session.end(LogoutReason.IDLE)
        assertEquals(ReleaseState.Login(null), vm.state.value)
    }

    @Test fun newViewModel_afterPreviousReady_startsAtLoginAndEndsStaleSession() = runBlocking {
        val old = readyVm()
        assertEquals(ReleaseState.Ready, old.state.value)
        val fresh = vm()
        assertEquals(ReleaseState.Login(), fresh.state.value)
        assertNull(session.state.value)
        fresh.release("tok-1", stationInvalid = true) // cannot release without signing in
        assertEquals(0, stations.releaseCalls)
        assertEquals(station, store.config.first())
    }

    @Test fun release_withoutSession_returnsToLogin() = runBlocking {
        val vm = readyVm()
        stations.releaseHandler = { ApiResult.Err(ApiError(null, "net")) }
        vm.release("tok-1", stationInvalid = false) // -> Error, session kept
        vm.awaitSettled()
        session.end(LogoutReason.IDLE)
        vm.release("tok-1", stationInvalid = true)
        assertEquals(ReleaseState.Login(), vm.state.value)
        assertEquals(1, stations.releaseCalls)
        assertEquals(station, store.config.first())
    }

    @Test fun signedInIdle60s_timesOutAndLogsOutAdmin() = runBlocking {
        val vm = readyVm()
        idleClock.advance(Duration.ofSeconds(59))
        vm.checkIdle()
        assertEquals(ReleaseState.Ready, vm.state.value)
        vm.touch()
        idleClock.advance(Duration.ofSeconds(59))
        vm.checkIdle()
        assertEquals(ReleaseState.Ready, vm.state.value) // touch restarted the count
        idleClock.advance(Duration.ofSeconds(1))
        vm.checkIdle()
        assertEquals(ReleaseState.TimedOut, vm.state.value)
        assertEquals(1, fakeAuth.logoutCalls)
        assertNull(session.state.value)
        assertEquals(station, store.config.first())
    }

    @Test fun loginStepIdle_doesNotTimeOut() {
        val vm = vm()
        idleClock.advance(Duration.ofMinutes(5))
        vm.checkIdle()
        assertEquals(ReleaseState.Login(), vm.state.value)
    }

    @Test fun release_ok_logsOutThroughRepository() = runBlocking {
        val vm = readyVm()
        vm.release("tok-1", stationInvalid = false)
        vm.awaitSettled()
        assertEquals(1, fakeAuth.logoutCalls)
    }

    @Test fun dismissInReady_logsOutAdmin() = runBlocking {
        val vm = readyVm()
        vm.cancel() // what the dialog's dismiss/cancel calls
        assertNull(session.state.value)
        assertEquals(1, fakeAuth.logoutCalls)
    }
}
