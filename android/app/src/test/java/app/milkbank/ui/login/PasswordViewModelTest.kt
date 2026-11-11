package app.milkbank.ui.login

import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.session.SessionManager
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
class PasswordViewModelTest {
    private val auth = FakeAuthRepository()
    private val session = SessionManager(FakeClock(Instant.EPOCH)).also {
        it.start("tok", staffDto(mustChange = true))
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PasswordViewModel({ auth }, session, "새 비밀번호가 서로 다릅니다")

    @Test fun mismatch_noServerCall() = runTest {
        val v = vm()
        v.submit("old", "new1", "new2"); advanceUntilIdle()
        assertEquals("새 비밀번호가 서로 다릅니다", v.state.value.error)
        assertEquals(0, auth.changeCalls)
        assertNull(v.state.value.next)
    }

    @Test fun success_clearsFlag_keepsToken_goesToPatients() = runTest {
        val v = vm()
        v.submit("old", "new", "new"); advanceUntilIdle()
        assertEquals(Patients, v.state.value.next)
        assertFalse(session.state.value!!.staff.mustChangePassword)
        assertEquals("tok", session.state.value!!.token)
        assertEquals(1, auth.changeCalls)
    }

    @Test fun failure_showsServerMessageVerbatim_noFlagChange() = runTest {
        auth.changeHandler = { _, _ -> ApiResult.Err(ApiError(400, "현재 비밀번호가 올바르지 않습니다")) }
        val v = vm()
        v.submit("old", "new", "new"); advanceUntilIdle()
        assertEquals("현재 비밀번호가 올바르지 않습니다", v.state.value.error)
        assertTrue(session.state.value!!.staff.mustChangePassword)
        assertNull(v.state.value.next)
    }

    @Test fun submitWhileLoading_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        auth.changeHandler = { _, _ -> gate.await(); ApiResult.Ok(Unit) }
        val v = vm()
        v.submit("old", "new", "new"); advanceUntilIdle()
        v.submit("old", "new", "new"); advanceUntilIdle()
        assertEquals(1, auth.changeCalls)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(Patients, v.state.value.next)
    }

    @Test fun usesTheRepositoryCurrentAtCallTime() = runTest {
        val first = FakeAuthRepository()
        val second = FakeAuthRepository()
        var current: FakeAuthRepository = first
        val v = PasswordViewModel({ current }, session, "새 비밀번호가 서로 다릅니다")
        current = second // server re-registered after the screen was created
        v.submit("old", "new", "new"); advanceUntilIdle()
        assertEquals(0, first.changeCalls)
        assertEquals(1, second.changeCalls)
        v.logout()
        assertEquals(0, first.logoutCalls)
        assertEquals(1, second.logoutCalls)
    }

    @Test fun emptyFields_doNothing() = runTest {
        val v = vm()
        v.submit("", "new", "new"); advanceUntilIdle()
        v.submit("old", "", ""); advanceUntilIdle()
        assertEquals(0, auth.changeCalls)
    }

    @Test fun logout_callsRepositoryImmediately() = runTest {
        val v = vm()
        v.logout() // no dispatcher turn needed: the repository ends the session itself
        assertEquals(1, auth.logoutCalls)
    }

    @Test fun logoutWhileLoading_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        auth.changeHandler = { _, _ -> gate.await(); ApiResult.Ok(Unit) }
        val v = vm()
        v.submit("old", "new", "new"); advanceUntilIdle()
        v.logout(); advanceUntilIdle()
        assertEquals(0, auth.logoutCalls)
        gate.complete(Unit); advanceUntilIdle()
    }

    @Test fun successAfterSessionEnded_goesToLogin() = runTest {
        auth.changeHandler = { _, _ ->
            session.end(app.milkbank.session.LogoutReason.EXPIRED)
            ApiResult.Ok(Unit)
        }
        val v = vm()
        v.submit("old", "new", "new"); advanceUntilIdle()
        assertEquals(app.milkbank.ui.Login, v.state.value.next)
        assertNull(session.state.value)
    }
}
