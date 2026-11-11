package app.milkbank.ui.patients

import org.junit.Assert.assertEquals
import org.junit.Test

class BabySearchTest {
    private val list = listOf(patientDto(1, "DEMO-1"), patientDto(2, "DEMO-10"), patientDto(3, "ABC-5"))

    @Test fun exactMatchWinsOverPartial() = assertEquals(BabyMatch.Single(list[0]), matchBabies("demo-1", list))
    @Test fun singlePartial() = assertEquals(BabyMatch.Single(list[2]), matchBabies("bc", list))
    @Test fun manyPartial() = assertEquals(BabyMatch.Many(listOf(list[0], list[1])), matchBabies("DEMO", list))
    @Test fun none() = assertEquals(BabyMatch.None, matchBabies("zzz", list))
    @Test fun emptyAndBlank() {
        assertEquals(BabyMatch.Empty, matchBabies("", list))
        assertEquals(BabyMatch.Empty, matchBabies("   ", list))
    }
    @Test fun trimsAndIgnoresCase() = assertEquals(BabyMatch.Single(list[2]), matchBabies("  abc-5 ", list))
    @Test fun doesNotMatchBabyName() = assertEquals(BabyMatch.None, matchBabies("이름1", list))
}
