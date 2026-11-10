package app.milkbank.server

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

private const val PIN_PREFIX = "sha256/"

/** "sha256/" + standard Base64 of SHA-256 over the DER SubjectPublicKeyInfo. Same as the server's tls.spki_pin. */
fun spkiPin(cert: X509Certificate): String = spkiPin(cert.publicKey.encoded)

internal fun spkiPin(spkiDer: ByteArray): String =
    PIN_PREFIX + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))

/**
 * Trusts exactly one server: the one whose leaf public key matches [pin]. There is no fallback to
 * system trust, no chain building, and no expiry/hostname checks (a self-signed cert is expected);
 * the pin is the only authority.
 */
class SpkiPinTrustManager(private val pin: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
        val actual = spkiPin(chain[0].publicKey.encoded).toByteArray(Charsets.UTF_8)
        val expected = pin.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(actual, expected)) {
            throw CertificateException("server public key does not match the registered pin")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
