package app.milkbank.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class BarChartTest {
    @Test fun barFractions_scalesToMax() {
        assertEquals(listOf(0.5f, 1f, 0f, 0.25f), barFractions(listOf(2, 4, 0, 1)))
    }

    @Test fun barFractions_allZero() {
        assertEquals(listOf(0f, 0f, 0f), barFractions(listOf(0, 0, 0)))
    }

    @Test fun thinLabels_30days_keepsEveryFifth() {
        val labels = (1..30).map { "d$it" }
        val out = thinLabels(labels, 5)
        assertEquals(30, out.size)
        assertEquals(listOf("d1", "d6", "d11", "d16", "d21", "d26"), out.filter { it.isNotEmpty() })
    }

    @Test fun thinLabels_7days_untouched() {
        val labels = (1..7).map { "d$it" }
        assertEquals(labels, thinLabels(labels, 5))
    }

    @Test fun barFractions_empty() {
        assertEquals(emptyList<Float>(), barFractions(emptyList()))
    }
}
