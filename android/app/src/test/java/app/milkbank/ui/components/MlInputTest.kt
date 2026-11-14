package app.milkbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MlInputTest {
    @Test fun blank_isEmpty() {
        assertEquals(MlParse.Empty, parseMl(""))
        assertEquals(MlParse.Empty, parseMl("  "))
    }

    @Test fun digitsInRange_areValid() {
        assertEquals(MlParse.Valid(240), parseMl("240"))
        assertEquals(MlParse.Valid(240), parseMl(" 240 "))
        assertEquals(MlParse.Valid(1), parseMl("1"))
        assertEquals(MlParse.Valid(20000), parseMl("20000"))
    }

    @Test fun outOfRangeOrNotDigits_areInvalid() {
        for (s in listOf("0", "20001", "abc", "-5", "1.5", "+5", "9999999999999")) {
            assertEquals(s, MlParse.Invalid, parseMl(s))
        }
    }
}
