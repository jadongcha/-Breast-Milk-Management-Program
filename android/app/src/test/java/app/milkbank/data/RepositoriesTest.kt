package app.milkbank.data

import app.milkbank.api.AuthInterceptor
import app.milkbank.api.MilkbankApi
import app.milkbank.api.PatientForm
import app.milkbank.api.StaffDto
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.core.FakeClock
import app.milkbank.server.HttpClientFactory
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import kotlinx.coroutines.asCoroutineDispatcher
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
        val r = HttpPatientRepository(api, net).list(null)
        assertEquals(ApiResult.Err(ApiError(401, "인증이 필요합니다")), r)
        assertEquals(LogoutReason.EXPIRED, session.logoutReason.value)
        assertNull(session.state.value)
    }

    @Test
    fun loginUnauthorizedDoesNotEndSession() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"아이디 또는 비밀번호가 올바르지 않습니다"}"""))
        val r = auth().login("kim", "bad")
        assertEquals(401, (r as ApiResult.Err).error.status)
        assertNull(session.logoutReason.value)
        assertNotNull(session.state.value)
    }

    @Test
    fun passwordRequiredIsEmitted() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"비밀번호를 먼저 변경해야 합니다"}"""))
        val r = HttpPatientRepository(api, net).list(null)
        assertEquals(403, (r as ApiResult.Err).error.status)
        assertTrue(session.passwordRequired.value)
        assertNotNull(session.state.value)
    }

    @Test
    fun otherForbiddenDoesNotEmit() = runTest {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"권한이 없습니다"}"""))
        HttpPatientRepository(api, net).list(null)
        assertEquals(false, session.passwordRequired.value)
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
        val r = HttpStockRepository(api, net).add(2, "discard", 2, "contaminated", "m", 240) as ApiResult.Ok
        assertEquals(4, r.value.balance)
        val req = server.takeRequest()
        assertEquals("/patients/2/records", req.path)
        val b = req.body.readUtf8()
        assertTrue(b, b.contains("\"discard_reason\":\"contaminated\"") && b.contains("\"kind\":\"discard\"") && b.contains("\"ml\":240"))
    }

    private fun gatedServer(latch: java.util.concurrent.CountDownLatch, response: () -> MockResponse) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return response()
            }
        }
    }

    @Test
    fun logoutEndsSessionBeforeTheNetworkCallCompletes() = runBlocking {
        session.start("tok", staff)
        val latch = java.util.concurrent.CountDownLatch(1)
        gatedServer(latch) { MockResponse() }
        val job = auth().logout()
        assertNull(session.state.value) // already on the login screen path
        assertEquals(LogoutReason.USER, session.logoutReason.value)
        assertTrue(job.isActive) // the server call is still pending
        latch.countDown()
        job.join()
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun logoutEndsSessionEvenOnNetworkFailure() = runBlocking {
        session.start("tok", staff)
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        auth().logout().join()
        assertNull(session.state.value)
        assertEquals(LogoutReason.USER, session.logoutReason.value)
    }

    @Test
    fun logoutEndsSessionEvenOnServerError() = runBlocking {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(500))
        auth().logout().join()
        assertNull(session.state.value)
    }

    @Test
    fun logoutUnauthorizedDoesNotReportExpired() = runBlocking {
        session.start("tok", staff)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"인증이 필요합니다"}"""))
        auth().logout().join()
        assertEquals(LogoutReason.USER, session.logoutReason.value)
    }

    @Test
    fun staleLogoutCallNeverEndsNewerSession() = runBlocking {
        session.start("A", staff)
        val latch = java.util.concurrent.CountDownLatch(1)
        gatedServer(latch) { MockResponse().setResponseCode(401) }
        val job = auth().logout()
        session.start("B", staff) // the next nurse logs in while the logout call is in flight
        latch.countDown()
        job.join()
        assertEquals("Bearer A", server.takeRequest().getHeader("Authorization"))
        assertEquals("B", session.state.value?.token)
    }

    @Test
    fun logoutWithoutServerStillEnds() = runBlocking {
        session.start("tok", staff)
        HttpAuthRepository(null, session, net).logout().join()
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

    @Test
    fun patchBodyAlwaysEncodesAllFields() = runBlocking {
        session.start("tok", staff)
        val body = """{"id":2,"reg_no":"R1","baby_name":"a","bed":"3","guardian_name":"m","guardian_phone":"010",
            "status":"admitted","admitted_on":"2026-11-01","discharged_on":null,"alert_enabled":true,
            "days_threshold":null,"packs_threshold":null,"balance":0}"""
        server.enqueue(MockResponse().setBody(body).addHeader("Content-Type", "application/json"))
        val form = PatientForm("R1", "a", "3", "m", "010", null, true, null, null)
        HttpPatientRepository(api, net).update(2, form)
        val b = server.takeRequest().body.readUtf8()
        assertTrue(
            b,
            b.contains("\"alert_enabled\":true") && b.contains("\"days_threshold\":null") &&
                b.contains("\"packs_threshold\":null"),
        )
    }

    @Test
    fun wrongShapeBodyIsErrNotCrash() = runBlocking {
        session.start("tok", staff)
        server.enqueue(MockResponse().setBody("""{"unexpected":1}""").addHeader("Content-Type", "application/json"))
        val r = HttpPatientRepository(api, net).detail(2)
        assertEquals(ApiResult.Err(ApiError(null, net)), r)
    }

    @Test
    fun badTimestampIsErrNotCrash() = runBlocking {
        session.start("tok", staff)
        val body = """{"record":${rec(1, "not-a-time")},"balance":1}"""
        server.enqueue(MockResponse().setBody(body).addHeader("Content-Type", "application/json"))
        assertEquals(ApiResult.Err(ApiError(null, net)), HttpStockRepository(api, net).cancel(1))
    }

    @Test
    fun staleUnauthorizedDoesNotEndNewerSession() = runBlocking {
        session.start("A", staff)
        val relogin = object : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
                val resp = chain.proceed(chain.request())
                session.start("B", staff) // user logged in again while the request was in flight
                return resp
            }
        }
        // Interceptors run outermost first: AuthInterceptor sees the response before relogin does,
        // so order relogin inside it to simulate the swap happening before the 401 is handled.
        val c = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).addInterceptor(relogin).build()
        val a = HttpClientFactory.retrofit(ServerConfig(server.url("/").toString(), "x"), c)
            .create(MilkbankApi::class.java)
        server.enqueue(MockResponse().setResponseCode(401))
        HttpPatientRepository(a, net).list(null)
        assertEquals("B", session.state.value?.token)
        assertNull(session.logoutReason.value)
        c.dispatcher.executorService.shutdown()
    }

    @Test
    fun pingReflectsHealth() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertTrue(auth().ping())
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(false, auth().ping())
        assertEquals(false, HttpAuthRepository(null, session, net).ping())
    }

    @Test
    fun healthCleanupRunsOnInjectedDispatcherNotCaller() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "fake-io") }
        val cleanupThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            val repo = HttpAuthRepository(
                api, session, net,
                healthApiFactory = { api },
                ioDispatcher = executor.asCoroutineDispatcher(),
                cleanup = { cleanupThreads += Thread.currentThread() },
            )
            val caller = Thread.currentThread()
            val r = runBlocking { repo.health(ServerConfig("https://x", "p")) }
            assertEquals(ApiResult.Ok(Unit), r)
            assertEquals(1, cleanupThreads.size)
            assertEquals("fake-io", cleanupThreads[0].name)
            assertTrue(cleanupThreads[0] !== caller)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun healthCleanupRunsEvenOnFailure() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        server.enqueue(MockResponse().setResponseCode(503))
        val repo = HttpAuthRepository(api, session, net, healthApiFactory = { api }, cleanup = { calls.incrementAndGet() })
        val r = runBlocking { repo.health(ServerConfig("https://x", "p")) }
        assertEquals(503, (r as ApiResult.Err).error.status)
        assertEquals(1, calls.get())
    }
}
