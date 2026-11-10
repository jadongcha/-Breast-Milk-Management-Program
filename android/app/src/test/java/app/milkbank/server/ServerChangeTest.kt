package app.milkbank.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerChangeTest {
    private val a = ServerConfig("https://a", "p1")

    @Test
    fun decisions() {
        assertEquals(false, shouldResetSession(null, a))
        assertEquals(false, shouldResetSession(a, a))
        assertEquals(true, shouldResetSession(a, a.copy(url = "https://b")))
        assertEquals(true, shouldResetSession(a, a.copy(pin = "p2")))
        assertEquals(true, shouldResetSession(a, null))
    }
}
