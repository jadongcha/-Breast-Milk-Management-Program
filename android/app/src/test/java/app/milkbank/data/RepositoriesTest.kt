package app.milkbank.data

import app.cash.turbine.test
import app.milkbank.api.AuthInterceptor
import app.milkbank.api.MilkbankApi
import app.milkbank.api.StaffDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RepositoriesTest {
    private val net = "서버에 연결할 수 없습니다"
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var session: SessionManager
    private lateinit var api: MilkbankApi
    private val staff = StaffDto(1, "kim", "김간호", "nurse", true, false)

    private val loginJson = """{"token":"tok-1","must_change_password":false,
        "staff":{"id":1,"username":"kim","name":"김간호","role":"nurse","active":true,"must_change_password":false},
        "extra":"ignored"}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = SessionManager(FakeClock(Instant.parse("2026-11-10T00:00:00Z")))
        client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).build()
        val cfg = ServerConfig(server.url("/").toString(), "unused")
        api = HttpClientFactory.retrofit(cfg, client).create(MilkbankApi::class.java)
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun auth() = HttpAuthRepository(api, session, net)

    @Test
    fun loginParsesAndStartsSession() = runBlocking {
        server.enqueue(MockResponse().setBody(loginJson).addHeader("Content-Type", "application/json"))
        val r = auth().login("kim", "pw") as ApiResult.Ok
        assertEquals("tok-1", r.value.token)
        assertEquals("김간호", r.value.staff.name)
        assertEquals("tok-1", session.state.value?.token)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun bearerIsAttached() = runBlocking {
        session.start("tok-9", staff)
        server.enqueue(MockResponse().setBody("[]").addHeader("Content-Type", "application/json"))
        HttpPatientRepository(api, net).list("a b")
        val req = server.takeRequest()
        assertEquals("Bearer tok-9", req.getHeader("Authorization"))
        assertEquals("/patients?q=a%20b", req.path)
    }

    @Test
    fun unauthorizedEndsSessionAsExpired() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"인증이 필요합니다"}"""))
        session.events.test {
            val r = HttpPatientRepository(api, net).list(null)
            assertEquals(ApiResult.Err(ApiError(401, "인증이 필요합니다")), r)
            assertEquals(LogoutReason.EXPIRED, awaitItem())
        }
        assertNull(session.state.value)
    }

    @Test
    fun loginUnauthorizedDoesNotEndSession() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"아이디 또는 비밀번호가 올바르지 않습니다"}"""))
        session.events.test {
            val r = auth().login("kim", "bad")
            assertEquals(401, (r as ApiResult.Err).error.status)
            expectNoEvents()
        }
        assertNotNull(session.state.value)
    }

    @Test
    fun passwordRequiredIsEmitted() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"비밀번호를 먼저 변경해야 합니다"}"""))
        session.passwordRequired.test {
            val r = HttpPatientRepository(api, net).list(null)
            assertEquals(403, (r as ApiResult.Err).error.status)
            awaitItem()
        }
        assertNotNull(session.state.value)
    }

    @Test
    fun otherForbiddenDoesNotEmit() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"권한이 없습니다"}"""))
        session.passwordRequired.test {
            HttpPatientRepository(api, net).list(null)
            expectNoEvents()
        }
    }

    private fun rec(id: Int, at: String) =
        """{"id":$id,"kind":"in","count":3,"discard_reason":null,"target_id":null,"memo":null,
           "staff_id":7,"staff_name":"박","created_at":"$at","canceled":false}"""

    @Test
    fun recordDtoParsesStaffIdAndTimes() = runBlocking {
        session.start("tok", staff)
        val body = """{"id":2,"reg_no":"R1","baby_name":"아기","bed":"3","guardian_name":"엄마",
            "guardian_phone":"010","status":"admitted","admitted_on":"2026-11-01","discharged_on":null,
            "alert_enabled":true,"days_threshold":null,"packs_threshold":null,"balance":3,
            "records":[${rec(1, "2026-11-10T15:30:00Z")},${rec(2, "2026-11-10T15:30:00+00:00")},${rec(3, "2026-11-10T15:30:00.123456")}]}"""
        server.enqueue(MockResponse().setBody(body).addHeader("Content-Type", "application/json"))
        val d = (HttpPatientRepository(api, net).detail(2) as ApiResult.Ok).value
        assertEquals(7, d.records[0].staffId)
        val expected = Instant.parse("2026-11-10T15:30:00Z")
        assertEquals(expected, d.records[0].createdAt)
        assertEquals(expected, d.records[1].createdAt)
        assertEquals(expected.plusNanos(123_456_000), d.records[2].createdAt)
    }

    @Test
    fun stockAddSendsBody() = runBlocking {
        session.start("tok", staff)
        val r0 = rec(5, "2026-11-10T00:00:00Z")
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"record":$r0,"balance":4}""")
                .addHeader("Content-Type", "application/json"),
        )
        val r = HttpStockRepository(api, net).add(2, "discard", 2, "contaminated", "m") as ApiResult.Ok
        assertEquals(4, r.value.balance)
        val req = server.takeRequest()
        assertEquals("/patients/2/records", req.path)
        val b = req.body.readUtf8()
        assertTrue(b, b.contains("\"discard_reason\":\"contaminated\"") && b.contains("\"kind\":\"discard\""))
    }

    @Test
    fun logoutEndsSessionEvenOnFailure() = runBlocking {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(500))
        auth().logout()
        assertNull(session.state.value)
    }

    @Test
    fun noServerReturnsNetworkError() = runBlocking {
        val r = HttpPatientRepository(null, net).list(null)
        assertEquals(ApiResult.Err(ApiError(null, net)), r)
    }

    @Test
    fun healthOkOn200() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val repo = HttpAuthRepository(api, session, net, healthApiFactory = { api })
        assertEquals(ApiResult.Ok(Unit), repo.health(ServerConfig("https://x", "p")))
    }

    @Test
    fun healthFailureIsErr() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val repo = HttpAuthRepository(api, session, net, healthApiFactory = { api })
        assertEquals(503, (repo.health(ServerConfig("https://x", "p")) as ApiResult.Err).error.status)
    }
}
