package app.milkbank.ui.guardian

import app.milkbank.core.ApiResult
import app.milkbank.core.isStationInvalid
import app.milkbank.data.StationRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Checks the station token with the server when the guardian home opens or resumes. */
class GuardianHomeGate(
    private val stations: () -> StationRepository,
    private val stationInvalid: MutableStateFlow<Boolean>,
) {
    /**
     * Returns the station name the server holds, or null. A 401 station_invalid raises the gate; other
     * errors (offline, server trouble) are ignored: the offline strip tells the user.
     */
    suspend fun check(token: String): String? = when (val r = stations().self(token)) {
        is ApiResult.Ok -> r.value.name
        is ApiResult.Err -> {
            if (r.error.isStationInvalid()) stationInvalid.value = true
            null
        }
    }
}
