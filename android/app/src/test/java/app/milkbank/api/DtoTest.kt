package app.milkbank.api

import kotlinx.serialization.decodeFromString
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

    private val staffJson = """{"id":1,"username":"kim","name":"김","role":"nurse","active":true,"must_change_password":false}"""

    @Test fun loginResponse_defaultsIdleTo10() {
        val r = json.decodeFromString<LoginResponse>("""{"token":"t","must_change_password":false,"staff":$staffJson}""")
        assertEquals(10, r.staffIdleMinutes)
    }

    @Test fun loginResponse_readsIdleMinutes() {
        val r = json.decodeFromString<LoginResponse>(
            """{"token":"t","must_change_password":false,"staff":$staffJson,"staff_idle_minutes":3}""",
        )
        assertEquals(3, r.staffIdleMinutes)
    }

    @Test fun settingsPatch_sendsOnlySetFields() {
        assertEquals("""{"avg_days":5}""", json.encodeToString(SettingsPatch(avgDays = 5)))
    }
}
