package app.milkbank.data

import app.milkbank.api.AuthInterceptor
import app.milkbank.api.MilkbankApi
import app.milkbank.api.SettingsPatch
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfig
import app.milkbank.session.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AdminRepositoryTest {
    private val net = "서버에 연결할 수 없습니다"
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repo: AdminRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val session = SessionManager(FakeClock(Instant.parse("2026-11-10T00:00:00Z")))
        client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).build()
        val api = HttpClientFactory.retrofit(ServerConfig(server.url("/").toString(), "unused"), client)
            .create(MilkbankApi::class.java)
        repo = HttpAdminRepository(api, net)
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    @Test
    fun exportCsv_returnsBytesUntouchedWithBom() = runBlocking {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "날짜,아기 ID\n".toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).addHeader("Content-Type", "text/csv; charset=utf-8"))
        val r = repo.exportCsv("2026-11-01", "2026-11-07") as ApiResult.Ok
        assertArrayEquals(bytes, r.value)
        assertEquals("/admin/export.csv?from=2026-11-01&to=2026-11-07", server.takeRequest().path)
    }

    @Test
    fun exportCsv_rangeErrorShowsServerDetail() = runBlocking {
        server.enqueue(json("""{"detail":"기간은 92일 이하로 골라 주세요"}""").setResponseCode(400))
        val r = repo.exportCsv("2026-01-01", "2026-12-31") as ApiResult.Err
        assertEquals("기간은 92일 이하로 골라 주세요", r.error.message)
    }

    @Test
    fun series_pathAndQuery() = runBlocking {
        server.enqueue(json("""[{"day":"2026-11-07","in_packs":2,"use_packs":1,"use_ml":null,"discard_packs":0}]"""))
        val r = repo.series(7, 3) as ApiResult.Ok
        assertEquals(1, r.value.size)
        assertEquals(null, r.value[0].useMl)
        assertEquals("/admin/series?days=7&patient_id=3", server.takeRequest().path)
    }

    @Test
    fun series_withoutPatientOmitsTheQuery() = runBlocking {
        server.enqueue(json("[]"))
        repo.series(30, null)
        assertEquals("/admin/series?days=30", server.takeRequest().path)
    }

    @Test
    fun daily_withAndWithoutDate() = runBlocking {
        val body = """{"date":"2026-11-07","rows":[{"patient_id":1,"baby_id":"A1","bed":"3","in_packs":2,"in_ml":480,
            "use_packs":1,"use_ml":null,"discard_packs":0,"discard_ml":null,"return_packs":0}],
            "total":{"in_packs":2,"in_ml":480,"use_packs":1,"use_ml":null,"discard_packs":0,"discard_ml":null,"return_packs":0}}"""
        server.enqueue(json(body)); server.enqueue(json(body))
        val r = repo.daily("2026-11-07") as ApiResult.Ok
        assertEquals("A1", r.value.rows[0].babyId)
        assertEquals(480, r.value.total.inMl)
        assertEquals("/admin/daily?date=2026-11-07", server.takeRequest().path)
        repo.daily(null)
        assertEquals("/admin/daily", server.takeRequest().path)
    }

    @Test
    fun overview_parses() = runBlocking {
        server.enqueue(
            json(
                """{"admitted":2,"low":1,"today_in_packs":3,"today_in_ml":null,"today_use_packs":1,
                "today_use_ml":240,"pending_submissions":1,"rows":[{"patient_id":1,"baby_id":"A1","bed":"3","balance":4,
                "daily_avg":null,"days_left":null,"status":"low","pending_packs":2}]}""",
            ),
        )
        val r = repo.overview() as ApiResult.Ok
        assertEquals(1, r.value.low)
        assertEquals(null, r.value.rows[0].dailyAvg)
        assertEquals("low", r.value.rows[0].status)
        assertEquals("/admin/overview", server.takeRequest().path)
    }

    @Test
    fun saveSettings_patchesOnlyTheGivenFieldsAsIntegers() = runBlocking {
        server.enqueue(json("""{"days_threshold":3,"packs_threshold":6,"avg_days":5,"staff_idle_minutes":10,"retention_days":30}"""))
        val r = repo.saveSettings(SettingsPatch(avgDays = 5)) as ApiResult.Ok
        assertEquals(5, r.value.avgDays)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/admin/settings", req.path)
        assertEquals("""{"avg_days":5}""", req.body.readUtf8())
    }

    @Test
    fun saveSettings_rangeErrorShowsServerDetail() = runBlocking {
        server.enqueue(json("""{"detail":"평균 계산 기간은 1~30일입니다"}""").setResponseCode(400))
        val r = repo.saveSettings(SettingsPatch(avgDays = 99)) as ApiResult.Err
        assertEquals("평균 계산 기간은 1~30일입니다", r.error.message)
    }

    @Test
    fun discharge_and_erase() = runBlocking {
        val patient = """{"id":4,"reg_no":"R","baby_name":"","bed":"3","guardian_name":"","guardian_phone":"",
            "status":"discharged","admitted_on":"2026-11-01","discharged_on":"2026-11-07","erased_at":"2026-11-08T00:00:00",
            "alert_enabled":true,"days_threshold":null,"packs_threshold":null,"balance":0}"""
        server.enqueue(json(patient)); server.enqueue(json(patient))
        val d = repo.discharge(4, "discard", "expired") as ApiResult.Ok
        assertEquals("2026-11-08T00:00:00", d.value.erasedAt)
        val req = server.takeRequest()
        assertEquals("/patients/4/discharge", req.path)
        assertEquals("""{"remaining":"discard","discard_reason":"expired"}""", req.body.readUtf8())
        repo.erase(4)
        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/patients/4/erase", req2.path)
        assertTrue(req2.body.size == 0L)
    }
}
