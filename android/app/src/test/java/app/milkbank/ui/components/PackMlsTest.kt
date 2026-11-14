package app.milkbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackMlsTest {
    @Test fun resize_keepsLeadingValues() {
        val s = PackMlsState(listOf("80", "90"))
        assertEquals(listOf("80", "90", ""), s.resize(3).texts)
        assertEquals(listOf("80"), s.resize(1).texts)
    }

    @Test fun empty_hasBlankFields() {
        assertEquals(listOf("", "", ""), PackMlsState.empty(3).texts)
    }

    @Test fun from_formatsValues() {
        assertEquals(listOf("80", "", "90"), PackMlsState.from(listOf(80, null, 90)).texts)
    }

    @Test fun fillAll_setsEveryField() {
        val s = PackMlsState.empty(3).setAt(1, "50").setAll("80")
        assertEquals(listOf("80", "80", "80"), s.texts)
        assertEquals("80", s.allText)
    }

    @Test fun fillAll_clearingDoesNotWipe() {
        val s = PackMlsState.empty(2).setAll("80").setAll("")
        assertEquals(listOf("80", "80"), s.texts)
        assertEquals("", s.allText)
    }

    @Test fun fillAll_invalidDoesNotFillButIsFlagged() {
        val s = PackMlsState(listOf("80", "")).setAll("1001")
        assertEquals(listOf("80", ""), s.texts)
        assertTrue(s.allInvalid)
        assertFalse(PackMlsState.empty(1).setAll("80").allInvalid)
    }

    @Test fun setAt_changesOnlyThatField() {
        assertEquals(listOf("1", "7", ""), PackMlsState(listOf("1", "2", "")).setAt(1, "7").texts)
    }

    @Test fun values_nullWhenAnyInvalid() {
        assertNull(PackMlsState(listOf("80", "1001")).values)
        assertNull(PackMlsState(listOf("80", "0")).values)
        assertNull(PackMlsState(listOf("80", "abc")).values)
        assertEquals(setOf(1), PackMlsState(listOf("80", "abc", "")).invalidIndexes)
    }

    @Test fun values_emptyFieldIsUnknown() {
        assertEquals(listOf<Int?>(null, null), PackMlsState.empty(2).values)
        assertEquals(listOf<Int?>(80, null), PackMlsState(listOf(" 80 ", "")).values)
        assertEquals(emptySet<Int>(), PackMlsState.empty(2).invalidIndexes)
    }

    @Test fun parsePackMl_bounds() {
        assertEquals(ParsedPackMl.Valid(1), parsePackMl("1"))
        assertEquals(ParsedPackMl.Valid(1000), parsePackMl("1000"))
        assertEquals(ParsedPackMl.Invalid, parsePackMl("0"))
        assertEquals(ParsedPackMl.Invalid, parsePackMl("1001"))
        assertEquals(ParsedPackMl.Invalid, parsePackMl("-5"))
        assertEquals(ParsedPackMl.Invalid, parsePackMl("8.5"))
        assertEquals(ParsedPackMl.Empty, parsePackMl("  "))
    }
}
