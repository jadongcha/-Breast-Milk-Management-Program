package app.milkbank.ui.guardian

import app.milkbank.api.StationCreateResponse
import app.milkbank.api.StationDto
import app.milkbank.api.StationSelfDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.data.StationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianHomeGateTest {
    private class FakeStations(var selfHandler: suspend (String) -> ApiResult<StationSelfDto>) : StationRepository {
        var token: String? = null
        override suspend fun create(name: String): ApiResult<StationCreateResponse> = error("unused")
        override suspend fun list(): ApiResult<List<StationDto>> = error("unused")
        override suspend fun deactivate(id: Int): ApiResult<StationDto> = error("unused")
        override suspend fun self(stationToken: String): ApiResult<StationSelfDto> {
            token = stationToken; return selfHandler(stationToken)
        }
        override suspend fun releaseSelf(stationToken: String): ApiResult<Unit> = error("unused")
    }

    private val invalid = MutableStateFlow(false)

    @Test fun selfCheck_invalid_setsGate() = runBlocking {
        val repo = FakeStations { ApiResult.Err(ApiError(401, "x", "station_invalid")) }
        assertNull(GuardianHomeGate({ repo }, invalid).check("tok"))
        assertEquals("tok", repo.token)
        assertEquals(true, invalid.value)
    }

    @Test fun selfCheck_ok_returnsServerName() = runBlocking {
        val repo = FakeStations { ApiResult.Ok(StationSelfDto(1, "새 이름")) }
        assertEquals("새 이름", GuardianHomeGate({ repo }, invalid).check("tok"))
        assertEquals(false, invalid.value)
    }

    @Test fun selfCheck_networkError_ignored() = runBlocking {
        val repo = FakeStations { ApiResult.Err(ApiError(null, "net")) }
        assertNull(GuardianHomeGate({ repo }, invalid).check("tok"))
        assertEquals(false, invalid.value)
    }

    @Test fun selfCheck_otherHttpError_doesNotInvalidate() = runBlocking {
        val repo = FakeStations { ApiResult.Err(ApiError(500, "x")) }
        assertNull(GuardianHomeGate({ repo }, invalid).check("tok"))
        assertEquals(false, invalid.value)
    }

    @Test fun staffSessionBackstop() {
        assertEquals(true, shouldDropStaffSession(true, releaseDialogOpen = false, hasSession = true))
        assertEquals(false, shouldDropStaffSession(true, releaseDialogOpen = true, hasSession = true))
        assertEquals(false, shouldDropStaffSession(true, releaseDialogOpen = false, hasSession = false))
        assertEquals(false, shouldDropStaffSession(false, releaseDialogOpen = false, hasSession = true))
    }
}
