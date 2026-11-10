package app.milkbank.server

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationTest {
    private val pinA = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val pinB = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    @Test fun first_saves() =
        assertEquals(RegistrationDecision.SAVE, decideRegistration(null, ServerConfig("https://a:1", pinA)))

    @Test fun samePinDifferentUrl_saves() =
        assertEquals(RegistrationDecision.SAVE, decideRegistration(ServerConfig("https://a:1", pinA), ServerConfig("https://b:2", pinA)))

    @Test fun differentPin_confirms() =
        assertEquals(RegistrationDecision.CONFIRM_DIFFERENT_SERVER, decideRegistration(ServerConfig("https://a:1", pinA), ServerConfig("https://a:1", pinB)))
}
