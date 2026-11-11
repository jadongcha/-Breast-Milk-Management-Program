package app.milkbank.core

data class ApiError(val status: Int?, val message: String, val code: String? = null)

/** Server error code: this tablet is not a registered (active) submission station. */
const val STATION_INVALID = "station_invalid"

fun ApiError.isStationInvalid(): Boolean = code == STATION_INVALID

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}
