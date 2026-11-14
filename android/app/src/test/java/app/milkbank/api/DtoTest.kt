package app.milkbank.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoTest {
    // Same configuration as HttpClientFactory.
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun confirmRequest_serializesNullMl() {
        val s = json.encodeToString(ConfirmRequest(3, null))
        assertTrue(s, s.contains("\"ml\":null"))
    }

    @Test fun confirmRequest_serializesMl() {
        assertEquals("""{"count":3,"ml":240}""", json.encodeToString(ConfirmRequest(3, 240)))
    }
}
