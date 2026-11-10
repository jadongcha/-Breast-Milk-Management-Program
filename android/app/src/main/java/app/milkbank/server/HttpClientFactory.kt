package app.milkbank.server

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

object HttpClientFactory {

    fun okHttp(
        config: ServerConfig,
        interceptors: List<Interceptor> = emptyList(),
        debugLogging: Boolean = false,
    ): OkHttpClient {
        val trustManager = SpkiPinTrustManager(config.pin)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pin identifies the server; the registered address is often a bare IP that a
            // self-signed cert would not list. Safe only because this client trusts one pinned key.
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)) // TLS only, no cleartext
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
        interceptors.forEach { builder.addInterceptor(it) }
        if (debugLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                },
            )
        }
        return builder.build()
    }

    fun retrofit(config: ServerConfig, client: OkHttpClient): Retrofit {
        val url = config.url.toHttpUrl()
        val base = url.newBuilder()
            .username("")
            .password("")
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
