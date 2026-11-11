package app.milkbank.ui.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TestQrFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missingFile_returnsNull() {
        assertNull(readTestQr(tmp.root))
    }

    @Test
    fun missingDir_returnsNull() {
        assertNull(readTestQr(File(tmp.root, "nope")))
    }

    @Test
    fun nullDir_returnsNull() {
        assertNull(readTestQr(null))
    }

    @Test
    fun blankFile_returnsNull() {
        File(tmp.root, "qr.txt").writeText("  \n\t ")
        assertNull(readTestQr(tmp.root))
    }

    @Test
    fun content_isTrimmed() {
        File(tmp.root, "qr.txt").writeText("\n  {\"a\":1} \r\n")
        assertEquals("{\"a\":1}", readTestQr(tmp.root))
    }
}
