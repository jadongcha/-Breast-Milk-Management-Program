package app.milkbank.server

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class SpkiPinTrustManagerTest {
    private fun cert(): X509Certificate =
        HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("localhost").build().certificate

    private fun independentPin(spki: ByteArray): String =
        "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))

    @Test
    fun pinFormat_matchesServerSide() {
        val held = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("localhost").build()
        val pin = spkiPin(held.certificate)
        assertEquals(51, pin.length)
        assertTrue(pin.startsWith("sha256/"))
        assertEquals(independentPin(held.keyPair.public.encoded), pin)
    }

    @Test
    fun pinFormat_knownAnswer() {
        val spki = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAHI9CV8nulaCFKD/p7iKKVT78y7t59tLkqqIMJ7uqgfwPqpuz+SpiFbFaE3OG/EzJUxucUnZ9vh36GlU+lD1lA==",
        )
        // sanity: the hard-coded bytes really are a valid EC SubjectPublicKeyInfo
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        assertEquals(spki.toList(), key.encoded.toList())
        val pin = spkiPin(key.encoded)
        assertEquals(51, pin.length)
        assertEquals(independentPin(spki), pin)
    }

    @Test
    fun matchingPin_accepts() {
        val c = cert()
        SpkiPinTrustManager(spkiPin(c)).checkServerTrusted(arrayOf(c), "ECDHE_ECDSA")
    }

    @Test
    fun wrongPin_rejects() {
        try {
            SpkiPinTrustManager(spkiPin(cert())).checkServerTrusted(arrayOf(cert()), "ECDHE_ECDSA")
            fail("expected CertificateException")
        } catch (_: CertificateException) {
        }
    }

    @Test
    fun emptyOrNullChain_rejects() {
        val tm = SpkiPinTrustManager(spkiPin(cert()))
        for (chain in listOf<Array<X509Certificate>?>(null, emptyArray())) {
            try {
                tm.checkServerTrusted(chain, "ECDHE_ECDSA")
                fail("expected CertificateException")
            } catch (_: CertificateException) {
            }
        }
    }

    @Test
    fun onlyLeafIsChecked() {
        val leaf = cert()
        val other = cert()
        try {
            SpkiPinTrustManager(spkiPin(other)).checkServerTrusted(arrayOf(leaf, other), "ECDHE_ECDSA")
            fail("expected CertificateException")
        } catch (_: CertificateException) {
        }
    }

    @Test
    fun clientTrusted_alwaysThrows_andNoIssuers() {
        val c = cert()
        val tm = SpkiPinTrustManager(spkiPin(c))
        try {
            tm.checkClientTrusted(arrayOf(c), "RSA")
            fail("expected CertificateException")
        } catch (_: CertificateException) {
        }
        assertEquals(0, tm.acceptedIssuers.size)
    }
}
