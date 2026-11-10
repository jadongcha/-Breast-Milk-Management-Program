package app.milkbank.api

import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.io.IOException

private val errorJson = Json { ignoreUnknownKeys = true }

/** Extracts a string `detail` from an error body, or null. */
fun parseDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        val detail = (errorJson.parseToJsonElement(body) as? JsonObject)?.get("detail")
        (detail as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    } catch (e: Exception) {
        null
    }
}

suspend fun <T> apiCall(networkErrorMessage: String, block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        ApiResult.Err(ApiError(e.code(), parseDetail(body) ?: networkErrorMessage))
    } catch (e: IOException) {
        ApiResult.Err(ApiError(null, networkErrorMessage))
    }
