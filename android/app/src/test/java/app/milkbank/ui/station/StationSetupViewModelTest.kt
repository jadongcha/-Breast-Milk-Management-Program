package app.milkbank.ui.station

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.milkbank.api.StaffDto
import app.milkbank.api.StationCreateResponse
import app.milkbank.api.StationDto
import app.milkbank.api.StationSelfDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.Clock
import app.milkbank.data.AuthRepository
import app.milkbank.data.StationRepository
import app.milkbank.session.LogoutReason
import app.milkbank.ui.login.FakeAuthRepository
import app.milkbank.session.SessionManager
import app.milkbank.station.StationConfig
import app.milkbank.station.StationStore
import app.milkbank.station.TokenCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StationSetupViewModelTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(cipher: String) = cipher.removePrefix("enc:")
    }

    private class FakeStations : StationRepository {
        var createCalls = 0
        var createHandler: suspend (String) -> ApiResult<StationCreateResponse> = { n ->
            ApiResult.Ok(StationCreateResponse(StationDto(1, n, true, Instant.EPOCH), "tok-1"))
        }
        override suspend fun create(name: String): ApiResult<StationCreateResponse> {
            createCalls++; return createHandler(name)
        }
        override suspend fun list(): ApiResult<List<StationDto>> = ApiResult.Ok(emptyList())
        override suspend fun deactivate(id: Int): ApiResult<StationDto> = error("unused")
        override suspend fun self(stationToken: String): ApiResult<StationSelfDto> = error("unused")
        override suspend fun releaseSelf(stationToken: String): ApiResult<Unit> = error("unused")
    }

    private val repo = FakeStations()
    private val fakeAuth = FakeAuthRepository()
    private val auth: AuthRepository = object : AuthRepository by fakeAuth {
        override fun logout() = fakeAuth.logout().also { session.end(LogoutReason.USER) }
    }
    private lateinit var store: StationStore
    private val session = SessionManager(object : Clock { override fun now(): Instant = Instant.EPOCH })

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val ds: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { tempFolder.newFile("s.preferences_pb") })
        store = StationStore(ds, FakeCipher())
        session.start("staff-token", StaffDto(1, "admin", "관리자", "admin", true, false))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private fun vm() = StationSetupViewModel({ repo }, { auth }, store, session, "서버에 연결할 수 없습니다")

    private suspend fun StationSetupViewModel.awaitSettled() =
        withTimeout(5000) { state.first { it !is SetupState.Submitting } }

    @Test fun setup_savesThenEndsSession() = runBlocking {
        // At the moment the session ends, record what the store holds.
        var savedAtEnd: StationConfig? = null
        val ended = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Unconfined) {
            session.state.collect { s ->
                if (s == null) {
                    savedAtEnd = store.config.first()
                    ended.complete(Unit)
                }
            }
        }
        val vm = vm()
        vm.onName("  신생아실 A ")
        assertEquals(SetupState.Confirming("신생아실 A"), vm.state.value)
        vm.confirm()
        assertEquals(SetupState.Done, vm.awaitSettled())
        withTimeout(5000) { ended.await() }
        job.cancel()
        assertEquals(StationConfig("tok-1", "신생아실 A"), savedAtEnd)
        assertEquals(1, fakeAuth.logoutCalls) // through the repository, so the server is told too
        assertEquals(StationConfig("tok-1", "신생아실 A"), store.config.first())
        assertNull(session.state.value)
    }

    @Test fun setup_conflictShowsServerMessage() = runBlocking {
        repo.createHandler = { ApiResult.Err(ApiError(409, "이미 있는 제출처 이름입니다")) }
        val vm = vm()
        vm.onName("A")
        vm.confirm()
        assertEquals(SetupState.Error("이미 있는 제출처 이름입니다"), vm.awaitSettled())
        assertNull(store.config.first())
        assertNotNull(session.state.value)
    }

    @Test fun setup_blankName_notSent() = runBlocking {
        val vm = vm()
        vm.onName("   ")
        vm.confirm()
        assertEquals(0, repo.createCalls)
        assertEquals(SetupState.Idle, vm.state.value)
    }

    @Test fun setup_doubleConfirm_createsOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        repo.createHandler = { n ->
            gate.await()
            ApiResult.Ok(StationCreateResponse(StationDto(1, n, true, Instant.EPOCH), "tok-1"))
        }
        val vm = vm()
        vm.onName("A")
        vm.confirm()
        vm.confirm()
        gate.complete(Unit)
        assertEquals(SetupState.Done, vm.awaitSettled())
        assertEquals(1, repo.createCalls)
    }

    @Test fun setup_done_leavesNoLogoutReasonForRootToRedirect() = runBlocking {
        val vm = vm()
        vm.onName("A")
        vm.confirm()
        vm.awaitSettled()
        assertNull(session.state.value)
        assertNull(session.logoutReason.value)
    }
}
