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

    @Test fun barFractions_empty() {
        assertEquals(emptyList<Float>(), barFractions(emptyList()))
    }
}
