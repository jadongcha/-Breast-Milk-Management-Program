package app.milkbank.server

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

class HttpClientFactoryTest {
    private lateinit var server: MockWebServer
    private lateinit var serverCert: HeldCertificate
    private val clients = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        serverCert = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("localhost").build()
        val hc = HandshakeCertificates.Builder().heldCertificate(serverCert).build()
        server = MockWebServer()
        server.useHttps(hc.sslSocketFactory(), false)
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0) // IPv4 only: avoids localhost IPv6 fallback masking the TLS error
    }

    @After
    fun tearDown() {
        clients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        server.shutdown()
    }

    private fun client(pin: String, interceptors: List<Interceptor> = emptyList()): OkHttpClient {
        val cfg = ServerConfig("https://127.0.0.1:${server.port}", pin)
        return HttpClientFactory.okHttp(cfg, interceptors).also { clients += it }
    }

    private fun health() = Request.Builder().url(server.url("/health")).build()

    @Test
    fun matchingPin_connects() {
        server.enqueue(MockResponse().setBody("ok"))
        client(spkiPin(serverCert.certificate)).newCall(health()).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun wrongPin_fails() {
        val other = HeldCertificate.Builder().ecdsa256().build()
        server.enqueue(MockResponse().setBody("ok"))
        try {
            client(spkiPin(other.certificate)).newCall(health()).execute().close()
            fail("expected SSLException")
        } catch (_: SSLException) {
            // SSLHandshakeException and SSLPeerUnverifiedException are both SSLException
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun hostnameMismatch_isIgnored_pinIsWhatCounts() {
        server.enqueue(MockResponse().setBody("ok"))
        val cfg = ServerConfig("https://127.0.0.1:${server.port}", spkiPin(serverCert.certificate))
        val c = HttpClientFactory.okHttp(cfg).also { clients += it }
        c.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/health").build()).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun cleartextIsNotAllowed() {
        val c = client(spkiPin(serverCert.certificate))
        try {
            c.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/health").build()).execute().close()
            fail("expected exception")
        } catch (_: IOException) {
        }
    }

    @Test
    fun extraInterceptors_areApplied() {
        server.enqueue(MockResponse().setBody("ok"))
        val add = Interceptor { it.proceed(it.request().newBuilder().header("Authorization", "Bearer t").build()) }
        client(spkiPin(serverCert.certificate), listOf(add)).newCall(health()).execute().close()
        assertEquals("Bearer t", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun timeouts_areSet() {
        val c = client(spkiPin(serverCert.certificate))
        assertEquals(5000, c.connectTimeoutMillis)
        assertEquals(10000, c.readTimeoutMillis)
    }

    @Test
    fun retrofitBaseUrl_isNormalized() {
        val c = client(spkiPin(serverCert.certificate))
        val r = HttpClientFactory.retrofit(ServerConfig("https://10.0.0.5:8443/some/path?x=1", "sha256/x"), c)
        assertEquals("https://10.0.0.5:8443/", r.baseUrl().toString())
        val r2 = HttpClientFactory.retrofit(ServerConfig("https://user:pw@host.example", "sha256/x"), c)
        assertEquals("https://host.example/", r2.baseUrl().toString())
        assertTrue(r2.baseUrl().toString().endsWith("/"))
    }
}
