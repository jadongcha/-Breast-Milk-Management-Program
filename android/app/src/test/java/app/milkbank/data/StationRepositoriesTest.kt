package app.milkbank.data

import app.milkbank.api.AuthInterceptor
import app.milkbank.api.MilkbankApi
import app.milkbank.api.StaffDto
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.core.isStationInvalid
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfig
import app.milkbank.session.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class StationRepositoriesTest {
    private val net = "서버에 연결할 수 없습니다"
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var session: SessionManager
    private lateinit var api: MilkbankApi
    private val staff = StaffDto(1, "kim", "김간호", "nurse", true, false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = SessionManager(FakeClock(Instant.parse("2026-11-10T00:00:00Z")))
        client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).build()
        api = HttpClientFactory.retrofit(ServerConfig(server.url("/").toString(), "unused"), client)
            .create(MilkbankApi::class.java)
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    @Test
    fun guardianCalls_sendBothHeaders() = runBlocking {
        session.start("staff-tok", staff)
        server.enqueue(json("[]"))
        HttpGuardianRepository(api, net).babies("st", "gt")
        val req = server.takeRequest()
        assertEquals("st", req.getHeader("X-Station-Token"))
        assertEquals("Bearer gt", req.getHeader("Authorization"))
    }

    @Test
    fun guardianSubmit_sendsMlWhenGiven() = runBlocking {
        server.enqueue(
            json("""{"submission":{"id":9,"count":3,"ml":240,"submitted_at":"2026-11-10T05:13:00"},"balance":7,"pending_count":1}"""),
        )
        val r = HttpGuardianRepository(api, net).submit("st", "gt", 4, 3, 240) as ApiResult.Ok
        assertEquals(240, r.value.submission.ml)
        assertEquals("""{"patient_id":4,"count":3,"ml":240}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun guardianCalls_withoutStationToken_omitHeader() = runBlocking {
        val repo = HttpGuardianRepository(api, net)
        server.enqueue(json("""{"token":"gt","babies":[]}"""))
        repo.login(null, "01012345678", "B-1")
        server.enqueue(json("[]"))
        repo.babies(null, "gt")
        server.enqueue(
            json("""{"submission":{"id":9,"count":3,"submitted_at":"2026-11-10T05:13:00"},"balance":7,"pending_count":1}"""),
        )
        repo.submit(null, "gt", 4, 3, null)
        server.enqueue(MockResponse().setResponseCode(204))
        repo.logout(null, "gt").join()
        repeat(4) {
            val req = server.takeRequest()
            assertEquals(null, req.getHeader("X-Station-Token"))
        }
    }

    @Test
    fun guardianLogin_sendsStationTokenAndBody() = runBlocking {
        server.enqueue(
            json("""{"token":"gt","babies":[{"patient_id":4,"baby_id":"B-1","balance":5,"pending_count":2}]}"""),
        )
        val r = HttpGuardianRepository(api, net).login("st", "01012345678", "B-1") as ApiResult.Ok
        assertEquals("gt", r.value.token)
        assertEquals(4, r.value.babies[0].patientId)
        assertEquals(2, r.value.babies[0].pendingCount)
        val req = server.takeRequest()
        assertEquals("st", req.getHeader("X-Station-Token"))
        assertEquals("/guardian/login", req.path)
        assertEquals("""{"phone":"01012345678","baby_id":"B-1"}""", req.body.readUtf8())
    }

    @Test
    fun guardian401_doesNotEndStaffSession() = runBlocking {
        session.start("staff-tok", staff)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"세션이 끝났습니다"}"""))
        val r = HttpGuardianRepository(api, net).babies("st", "gt")
        assertEquals(401, (r as ApiResult.Err).error.status)
        assertNotNull(session.state.value)
        assertEquals(null, session.logoutReason.value)
    }

    @Test
    fun stationInvalid_codeIsParsed() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":"등록된 제출처 기기가 아닙니다","code":"station_invalid"}"""),
        )
        val r = HttpStationRepository(api, net).self("st") as ApiResult.Err
        assertTrue(r.error.isStationInvalid())
        assertEquals("등록된 제출처 기기가 아닙니다", r.error.message)
    }

    @Test
    fun guardianSubmit_sendsBody() = runBlocking {
        server.enqueue(
            json(
                """{"submission":{"id":9,"count":3,"submitted_at":"2026-11-10T05:13:00"},
                "balance":7,"pending_count":1}""",
            ),
        )
        val r = HttpGuardianRepository(api, net).submit("st", "gt", 4, 3, null) as ApiResult.Ok
        assertEquals(9, r.value.submission.id)
        assertEquals(Instant.parse("2026-11-10T05:13:00Z"), r.value.submission.submittedAt)
        assertEquals(1, r.value.pendingCount)
        val req = server.takeRequest()
        assertEquals("""{"patient_id":4,"count":3}""", req.body.readUtf8())
        assertEquals("Bearer gt", req.getHeader("Authorization"))
    }

    @Test
    fun guardianLogout_sendsBothHeadersAndSwallowsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        HttpGuardianRepository(api, net).logout("st", "gt").join()
        val req = server.takeRequest()
        assertEquals("/guardian/logout", req.path)
        assertEquals("st", req.getHeader("X-Station-Token"))
        assertEquals("Bearer gt", req.getHeader("Authorization"))
    }

    @Test
    fun pendingDto_parses() = runBlocking {
        session.start("tok", staff)
        server.enqueue(
            json(
                """[{"id":3,"patient_id":4,"baby_id":"B-1","balance":5,"station_name":"1층 로비",
                "submitted_at":"2026-11-10T05:13:00Z","count":2,"discharged":true,"extra":1}]""",
            ),
        )
        val p = (HttpSubmissionRepository(api, net).pending() as ApiResult.Ok).value.single()
        assertEquals(3, p.id)
        assertEquals("B-1", p.babyId)
        assertEquals("1층 로비", p.stationName)
        assertEquals(Instant.parse("2026-11-10T05:13:00Z"), p.submittedAt)
        assertTrue(p.discharged)
        val req = server.takeRequest()
        assertEquals("/submissions?status=pending", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun confirmAndReject_sendBodiesAndParse() = runBlocking {
        session.start("tok", staff)
        val sub = """{"id":3,"patient_id":4,"count":2,"submitted_at":"2026-11-10T05:13:00Z","status":"confirmed",
            "confirmed_count":2,"handled_by":1,"handled_at":"2026-11-10T05:20:00Z","reject_reason":null,"memo":null}"""
        server.enqueue(
            json(
                """{"record":{"id":8,"kind":"intake","count":2,"discard_reason":null,"target_id":null,"memo":null,
                "staff_id":1,"staff_name":"김","created_at":"2026-11-10T05:20:00Z","canceled":false},
                "balance":7,"submission":$sub}""",
            ),
        )
        val c = (HttpSubmissionRepository(api, net).confirm(3, 2, null) as ApiResult.Ok).value
        assertEquals(7, c.balance)
        assertEquals(2, c.submission.confirmedCount)
        val creq = server.takeRequest()
        assertEquals("/submissions/3/confirm", creq.path)
        assertEquals("""{"count":2,"ml":null}""", creq.body.readUtf8())

        server.enqueue(json("""{"submission":$sub}"""))
        HttpSubmissionRepository(api, net).reject(3, "mismatch", "메모")
        val rreq = server.takeRequest()
        assertEquals("/submissions/3/reject", rreq.path)
        assertEquals("""{"reason":"mismatch","memo":"메모"}""", rreq.body.readUtf8())
    }

    @Test
    fun releaseSelf_sendsStaffBearerAndStationToken() = runBlocking {
        session.start("staff-tok", staff)
        server.enqueue(MockResponse().setResponseCode(204))
        val r = HttpStationRepository(api, net).releaseSelf("st")
        assertEquals(ApiResult.Ok(Unit), r)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/station/self", req.path)
        assertEquals("st", req.getHeader("X-Station-Token"))
        assertEquals("Bearer staff-tok", req.getHeader("Authorization"))
    }
}
