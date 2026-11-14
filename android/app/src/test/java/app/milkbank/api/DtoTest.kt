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

    @Test fun confirmRequest_alwaysSerializesPackMls() {
        assertEquals("""{"count":2,"pack_mls":[80,null]}""", json.encodeToString(ConfirmRequest(2, listOf(80, null))))
        assertEquals("""{"count":0,"pack_mls":[]}""", json.encodeToString(ConfirmRequest(0, emptyList())))
    }

    @Test fun recordRequest_sendsPackMlsAndIdsOnlyWhenSet() {
        assertEquals(
            """{"kind":"intake","count":2,"pack_mls":[80,null]}""",
            json.encodeToString(RecordRequest("intake", 2, packMls = listOf(80, null))),
        )
        assertEquals(
            """{"kind":"use","count":1,"pack_ids":[5]}""",
            json.encodeToString(RecordRequest("use", 1, packIds = listOf(5))),
        )
    }

    @Test fun guardianSubmitRequest_packMls() {
        assertEquals(
            """{"patient_id":4,"count":2,"pack_mls":[null,90]}""",
            json.encodeToString(GuardianSubmitRequest(4, 2, listOf(null, 90))),
        )
    }

    @Test fun patientDetail_readsPacksAndUnknownCounts() {
        val d = json.decodeFromString<PatientDetailDto>(
            """{"id":1,"reg_no":"B-1","baby_name":"a","bed":"1","guardian_name":"g","guardian_phone":"p","status":"admitted",
            "admitted_on":"2026-11-01","alert_enabled":false,"balance":2,"balance_ml":80,"unknown_ml_packs":1,
            "records":[{"id":3,"kind":"in","count":2,"ml":80,"ml_unknown":1,"staff_id":1,"staff_name":"k",
            "created_at":"2026-11-10T00:00:00","canceled":false}],
            "packs":[{"id":7,"ml":80,"created_at":"2026-11-10T00:00:00"},{"id":8,"ml":null,"created_at":"2026-11-10T00:00:00"}]}""",
        )
        assertEquals(80, d.balanceMl)
        assertEquals(1, d.unknownMlPacks)
        assertEquals(1, d.records.single().mlUnknown)
        assertEquals(listOf(7, 8), d.packs.map { it.id })
        assertEquals(listOf<Int?>(80, null), d.packs.map { it.ml })
    }

    @Test fun pending_readsPackMls() {
        val p = json.decodeFromString<PendingDto>(
            """{"id":1,"patient_id":2,"baby_id":"B","balance":3,"station_name":"s","submitted_at":"2026-11-10T00:00:00",
            "count":2,"ml":80,"pack_mls":[80,null],"discharged":false}""",
        )
        assertEquals(listOf<Int?>(80, null), p.packMls)
    }

    @Test fun overviewRow_readsMlFields() {
        val r = json.decodeFromString<OverviewRowDto>(
            """{"patient_id":1,"baby_id":"B","bed":"1","balance":4,"balance_ml":260,"unknown_ml_packs":1,
            "daily_avg":null,"days_left":null,"status":"low","pending_packs":0}""",
        )
        assertEquals(260, r.balanceMl)
        assertEquals(1, r.unknownMlPacks)
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
