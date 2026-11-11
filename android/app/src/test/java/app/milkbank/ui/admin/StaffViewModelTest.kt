package app.milkbank.ui.admin

import app.milkbank.api.StaffCreateResponse
import app.milkbank.api.StaffDto
import app.milkbank.api.TempPasswordResponse
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.data.StaffRepository
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

private fun staffDto(id: Int, username: String, role: String = "nurse", active: Boolean = true) =
    StaffDto(id, username, "이름$id", role, active, false)

class FakeStaffRepository : StaffRepository {
    var listHandler: suspend () -> ApiResult<List<StaffDto>> =
        { ApiResult.Ok(listOf(staffDto(1, "admin", "admin"), staffDto(2, "kim"))) }
    var createHandler: suspend (String, String, String) -> ApiResult<StaffCreateResponse> =
        { u, n, r -> ApiResult.Ok(StaffCreateResponse(StaffDto(3, u, n, r, true, true), "Temp-1234")) }
    var activeHandler: suspend (Int, Boolean) -> ApiResult<StaffDto> =
        { id, a -> ApiResult.Ok(staffDto(id, "kim", active = a)) }
    var resetHandler: suspend (Int) -> ApiResult<TempPasswordResponse> = { ApiResult.Ok(TempPasswordResponse("Reset-5678")) }
    var createCalls = 0
    var activeCalls = 0
    var resetCalls = 0

    override suspend fun list() = listHandler()
    override suspend fun create(username: String, name: String, role: String): ApiResult<StaffCreateResponse> {
        createCalls++; return createHandler(username, name, role)
    }
    override suspend fun setActive(id: Int, active: Boolean): ApiResult<StaffDto> {
        activeCalls++; return activeHandler(id, active)
    }
    override suspend fun resetPassword(id: Int): ApiResult<TempPasswordResponse> {
        resetCalls++; return resetHandler(id)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StaffViewModelTest {
    private val repo = FakeStaffRepository()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StaffViewModel({ repo }, "서버에 연결할 수 없습니다")

    @Test fun refresh_loadsList() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        assertEquals(listOf("admin", "kim"), v.list.value.map { it.username })
    }

    @Test fun create_showsTempPassword_thenDismissClearsIt() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.create("park", "박간호", "nurse"); advanceUntilIdle()
        assertEquals(TempPassword("park", "Temp-1234"), v.tempPassword.value)
        assertEquals(3, v.list.value.size)
        assertFalse(v.submitting.value)
        v.dismissTempPassword()
        assertNull(v.tempPassword.value)
    }

    @Test fun create_error_showsServerMessage_noPassword() = runTest {
        repo.createHandler = { _, _, _ -> ApiResult.Err(ApiError(409, "이미 사용 중인 아이디입니다")) }
        val v = vm(); v.create("kim", "김", "nurse"); advanceUntilIdle()
        assertEquals("이미 사용 중인 아이디입니다", v.error.value)
        assertNull(v.tempPassword.value)
    }

    @Test fun create_invalidRole_isIgnored() = runTest {
        val v = vm(); v.create("x", "y", "root"); advanceUntilIdle()
        assertEquals(0, repo.createCalls)
    }

    @Test fun resetPassword_requiresConfirm_thenShowsTempPassword() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.requestResetPassword(2)
        assertEquals(StaffAction(2, "kim", StaffActionKind.RESET), v.pending.value)
        assertEquals(0, repo.resetCalls)
        v.confirm(); advanceUntilIdle()
        assertEquals(TempPassword("kim", "Reset-5678"), v.tempPassword.value)
        assertNull(v.pending.value)
        assertEquals(1, repo.resetCalls)
    }

    @Test fun deactivate_requiresConfirm_thenUpdatesList() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.requestSetActive(2, false)
        assertEquals(StaffAction(2, "kim", StaffActionKind.DEACTIVATE), v.pending.value)
        assertEquals(0, repo.activeCalls)
        v.confirm(); advanceUntilIdle()
        assertFalse(v.list.value.first { it.id == 2 }.active)
        assertNull(v.pending.value)
    }

    @Test fun activate_sendsTrue() = runTest {
        var sent: Boolean? = null
        repo.activeHandler = { id, a -> sent = a; ApiResult.Ok(staffDto(id, "kim", active = a)) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.requestSetActive(2, true); v.confirm(); advanceUntilIdle()
        assertEquals(true, sent)
    }

    @Test fun dismissPending_cancelsWithoutCall() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.requestSetActive(2, false); v.dismissPending()
        v.confirm(); advanceUntilIdle()
        assertEquals(0, repo.activeCalls)
    }

    @Test fun selfDeactivateRefused_showsServerMessageVerbatim() = runTest {
        repo.activeHandler = { _, _ -> ApiResult.Err(ApiError(400, "본인 계정은 비활성화할 수 없습니다")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.requestSetActive(1, false); v.confirm(); advanceUntilIdle()
        assertEquals("본인 계정은 비활성화할 수 없습니다", v.error.value)
        assertTrue(v.list.value.first { it.id == 1 }.active)
        assertNull(v.pending.value)
    }

    @Test fun forbidden403_message() = runTest {
        repo.listHandler = { ApiResult.Err(ApiError(403, "관리자만 사용할 수 있습니다")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        assertEquals("관리자만 사용할 수 있습니다", v.error.value)
    }

    @Test fun confirmTwice_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.resetHandler = { gate.await(); ApiResult.Ok(TempPasswordResponse("Reset-5678")) }
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.requestResetPassword(2)
        v.confirm(); v.confirm(); advanceUntilIdle()
        assertTrue(v.submitting.value)
        v.requestResetPassword(2); v.confirm(); advanceUntilIdle()
        assertEquals(1, repo.resetCalls)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, repo.resetCalls)
        assertFalse(v.submitting.value)
    }

    @Test fun createWhileSubmitting_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.createHandler = { u, _, _ -> gate.await(); ApiResult.Ok(StaffCreateResponse(staffDto(3, u), "T")) }
        val v = vm()
        v.create("a", "b", "nurse"); v.create("a", "b", "nurse"); advanceUntilIdle()
        assertEquals(1, repo.createCalls)
        gate.complete(Unit); advanceUntilIdle()
    }
}
