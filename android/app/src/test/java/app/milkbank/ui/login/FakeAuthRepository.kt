package app.milkbank.ui.login

import app.milkbank.api.LoginResponse
import app.milkbank.api.StaffDto
import app.milkbank.core.ApiResult
import app.milkbank.data.AuthRepository
import app.milkbank.server.ServerConfig

fun staffDto(mustChange: Boolean = false) =
    StaffDto(1, "nurse1", "김간호", "nurse", true, mustChange)

class FakeAuthRepository : AuthRepository {
    var loginHandler: suspend (String, String) -> ApiResult<LoginResponse> = { _, _ ->
        ApiResult.Ok(LoginResponse("tok", false, staffDto()))
    }
    var changeHandler: suspend (String, String) -> ApiResult<Unit> = { _, _ -> ApiResult.Ok(Unit) }
    var loginCalls = 0
    var changeCalls = 0
    var logoutCalls = 0

    override suspend fun login(username: String, password: String): ApiResult<LoginResponse> {
        loginCalls++
        return loginHandler(username, password)
    }

    override suspend fun logout(): ApiResult<Unit> {
        logoutCalls++
        return ApiResult.Ok(Unit)
    }

    override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
        changeCalls++
        return changeHandler(current, new)
    }

    override suspend fun health(config: ServerConfig): ApiResult<Unit> = ApiResult.Ok(Unit)
    override suspend fun ping(): Boolean = true
}
