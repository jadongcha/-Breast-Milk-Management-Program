package app.milkbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StockTextTest {
    // The real strings.xml, so the test notices if the wording and the code drift apart.
    private val xml = File("src/main/res/values/strings.xml").readText()
    private fun res(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1].removeSurrounding("\"")

    private val f = StockFormats(
        packs = { String.format(res("packs"), it) },
        withMl = { p, m -> String.format(res("stock_with_ml"), p, m) },
        unknownSuffix = { String.format(res("stock_unknown_suffix"), it) },
        allUnknown = { String.format(res("stock_all_unknown"), it) },
        recordPartial = { c, m, u -> String.format(res("record_ml_partial"), c, m, u) },
    )

    @Test fun stockText_allKnown() = assertEquals("4팩 · 340mL", stockTextOf(4, 340, 0, f))

    @Test fun stockText_partial() = assertEquals("4팩 · 260mL · 용량 모름 1팩", stockTextOf(4, 260, 1, f))

    @Test fun stockText_allUnknown() = assertEquals("2팩 · 용량 모름", stockTextOf(2, 0, 2, f))

    @Test fun stockText_zeroPacks_isPlainPacks() = assertEquals("0팩", stockTextOf(0, 0, 0, f))

    @Test fun recordMlText_allKnown() = assertEquals("3팩 · 240mL", recordMlTextOf(3, 240, 0, f))

    @Test fun recordMlText_partial() = assertEquals("3팩 · 160mL (용량 모름 1팩)", recordMlTextOf(3, 160, 1, f))

    @Test fun recordMlText_allUnknown() {
        assertEquals("3팩", recordMlTextOf(3, null, 3, f))
        assertEquals("3팩", recordMlTextOf(3, null, 0, f))
    }
}
