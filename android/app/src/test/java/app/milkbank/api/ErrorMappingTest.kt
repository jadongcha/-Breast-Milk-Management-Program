package app.milkbank.api

import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.isStationInvalid
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ErrorMappingTest {
    private val net = "서버에 연결할 수 없습니다"

    private fun http(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun detailIsPassedThrough() = runBlocking {
        val r = apiCall(net) { throw http(409, """{"detail":"재고가 부족합니다 (현재 0팩)"}""") }
        assertEquals(ApiResult.Err(ApiError(409, "재고가 부족합니다 (현재 0팩)")), r)
    }

    @Test
    fun missingBodyFallsBackToNetworkMessage() = runBlocking {
        val r = apiCall(net) { throw http(500, "") }
        assertEquals(ApiResult.Err(ApiError(500, net)), r)
    }

    @Test
    fun nonStringDetailFallsBack() = runBlocking {
        val r = apiCall(net) { throw http(422, """{"detail":[{"msg":"x"}]}""") }
        assertEquals(ApiResult.Err(ApiError(422, net)), r)
    }

    @Test
    fun ioExceptionHasNullStatus() = runBlocking {
        val r = apiCall(net) { throw IOException("down") }
        assertEquals(ApiResult.Err(ApiError(null, net)), r)
    }

    @Test
    fun okIsWrapped() = runBlocking {
        assertEquals(ApiResult.Ok(3), apiCall(net) { 3 })
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancellationIsRethrown() {
        runBlocking { apiCall(net) { throw kotlinx.coroutines.CancellationException("c") } }
    }

    @Test
    fun errorBodyCode_isParsed() = runBlocking {
        val r = apiCall(net) {
            throw http(401, """{"detail":"등록된 제출처 기기가 아닙니다","code":"station_invalid"}""")
        } as ApiResult.Err
        assertEquals("station_invalid", r.error.code)
        assertTrue(r.error.isStationInvalid())
    }

    @Test
    fun errorWithoutCode_isNotStationInvalid() = runBlocking {
        val r = apiCall(net) { throw http(401, """{"detail":"세션이 끝났습니다"}""") } as ApiResult.Err
        assertEquals(null, r.error.code)
        assertFalse(r.error.isStationInvalid())
    }
}
