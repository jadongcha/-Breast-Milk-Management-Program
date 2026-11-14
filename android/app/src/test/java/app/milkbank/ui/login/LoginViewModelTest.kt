package app.milkbank.ui.login

import app.milkbank.api.LoginResponse
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.session.SessionManager
import app.milkbank.ui.Password
import app.milkbank.ui.Patients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val auth = FakeAuthRepository()
    private val session = SessionManager(FakeClock(Instant.EPOCH))

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(user: String = "nurse1", pw: String = "pw") = LoginViewModel({ auth }, session).also {
        it.onUsernameChange(user)
        it.onPasswordChange(pw)
    }

    @Test fun success_normal_startsSessionAndGoesToPatients() = runTest {
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertEquals(Patients, v.state.value.next)
        assertEquals("tok", session.state.value?.token)
        assertFalse(v.state.value.loading)
        assertNull(v.state.value.error)
    }

    @Test fun success_mustChange_goesToPassword() = runTest {
        auth.loginHandler = { _, _ -> ApiResult.Ok(LoginResponse("tok", true, staffDto(true))) }
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertEquals(Password, v.state.value.next)
        assertTrue(session.state.value!!.staff.mustChangePassword)
    }

    @Test fun unauthorized_showsServerMessage_clearsPassword_keepsUsername() = runTest {
        auth.loginHandler = { _, _ -> ApiResult.Err(ApiError(401, "아이디 또는 비밀번호가 올바르지 않습니다")) }
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertEquals("아이디 또는 비밀번호가 올바르지 않습니다", v.state.value.error)
        assertEquals("", v.state.value.password)
        assertEquals("nurse1", v.state.value.username)
        assertNull(v.state.value.next)
        assertNull(session.state.value)
    }

    @Test fun tooManyAttempts_showsServerMessage() = runTest {
        auth.loginHandler = { _, _ -> ApiResult.Err(ApiError(429, "잠시 후 다시 시도해 주세요")) }
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertEquals("잠시 후 다시 시도해 주세요", v.state.value.error)
    }

    @Test fun networkError_showsMessage() = runTest {
        auth.loginHandler = { _, _ -> ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.state.value.error)
    }

    @Test fun submitWhileLoading_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        auth.loginHandler = { _, _ -> gate.await(); ApiResult.Ok(LoginResponse("tok", false, staffDto())) }
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertTrue(v.state.value.loading)
        v.submit(); advanceUntilIdle()
        assertEquals(1, auth.loginCalls)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, auth.loginCalls)
        assertEquals(Patients, v.state.value.next)
    }

    @Test fun emptyFields_doNotSubmit() = runTest {
        val v = vm(user = "", pw = "pw")
        v.submit(); advanceUntilIdle()
        val w = vm(user = "nurse1", pw = "")
        w.submit(); advanceUntilIdle()
        assertEquals(0, auth.loginCalls)
    }

    @Test fun editingClearsError() = runTest {
        auth.loginHandler = { _, _ -> ApiResult.Err(ApiError(401, "x")) }
        val v = vm()
        v.submit(); advanceUntilIdle()
        v.onPasswordChange("a")
        assertNull(v.state.value.error)
    }

    @Test fun usesTheRepositoryCurrentAtCallTime() = runTest {
        val stale = FakeAuthRepository()
        var current = stale
        val v = LoginViewModel({ current }, session)
        val fresh = FakeAuthRepository()
        current = fresh // server re-registered after the screen was created
        v.onUsernameChange("nurse1"); v.onPasswordChange("pw")
        v.submit(); advanceUntilIdle()
        assertEquals(0, stale.loginCalls)
        assertEquals(1, fresh.loginCalls)
    }

    @Test fun onNavigated_clearsNext() = runTest {
        val v = vm()
        v.submit(); advanceUntilIdle()
        v.onNavigated()
        assertNull(v.state.value.next)
    }

    @Test fun clear_whenIdle_resetsFields() = runTest {
        val v = vm()
        v.clear()
        assertEquals(LoginState(), v.state.value)
    }

    @Test fun clear_whileLoading_doesNothing() = runTest {
        val gate = CompletableDeferred<ApiResult<LoginResponse>>()
        auth.loginHandler = { _, _ -> gate.await() }
        val v = vm()
        v.submit(); advanceUntilIdle()
        assertTrue(v.state.value.loading)
        v.clear()
        assertTrue(v.state.value.loading)
        assertEquals("nurse1", v.state.value.username)
        assertEquals("pw", v.state.value.password)
        gate.complete(ApiResult.Ok(LoginResponse("tok", true, staffDto(false))))
        advanceUntilIdle()
    }
}
