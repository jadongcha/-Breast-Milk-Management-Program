package app.milkbank.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun json(type: String = "milkbank-server", v: String = "1", url: String = "https://10.0.0.5:8443", p: String = pin) =
        """{"type":"$type","v":$v,"url":"$url","pin":"$p"}"""

    @Test fun validQr_isOk() {
        assertEquals(QrParse.Ok(ServerConfig("https://10.0.0.5:8443", pin)), parseQr(json()))
    }

    @Test fun keyOrderDoesNotMatter() {
        val raw = """{"pin":"$pin","url":"https://h:1","v":1,"type":"milkbank-server"}"""
        assertEquals(QrParse.Ok(ServerConfig("https://h:1", pin)), parseQr(raw))
    }

    @Test fun notJson() = assertEquals(QrParse.NotServerQr, parseQr("hello world"))
    @Test fun emptyString() = assertEquals(QrParse.NotServerQr, parseQr(""))
    @Test fun wrongType() = assertEquals(QrParse.NotServerQr, parseQr(json(type = "other")))
    @Test fun versionTwo() = assertEquals(QrParse.NotServerQr, parseQr(json(v = "2")))
    @Test fun httpUrl() = assertEquals(QrParse.NotServerQr, parseQr(json(url = "http://10.0.0.5:8443")))
    @Test fun httpsWithoutHost() = assertEquals(QrParse.NotServerQr, parseQr(json(url = "https://")))
    @Test fun pinWithoutPrefix() = assertEquals(QrParse.NotServerQr, parseQr(json(p = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))
    @Test fun pinTooShort() = assertEquals(QrParse.NotServerQr, parseQr(json(p = "sha256/AAAA=")))
    @Test fun pinNoPadding() = assertEquals(QrParse.NotServerQr, parseQr(json(p = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))

    @Test fun missingFields() {
        assertEquals(QrParse.NotServerQr, parseQr("""{"type":"milkbank-server","v":1,"url":"https://h:1"}"""))
        assertEquals(QrParse.NotServerQr, parseQr("""{"type":"milkbank-server","v":1,"pin":"$pin"}"""))
        assertEquals(QrParse.NotServerQr, parseQr("""{"v":1,"url":"https://h:1","pin":"$pin"}"""))
        assertEquals(QrParse.NotServerQr, parseQr("""{"type":"milkbank-server","url":"https://h:1","pin":"$pin"}"""))
    }

    @Test fun nonObjectJson() = assertTrue(parseQr("[1,2]") is QrParse.NotServerQr)
}
