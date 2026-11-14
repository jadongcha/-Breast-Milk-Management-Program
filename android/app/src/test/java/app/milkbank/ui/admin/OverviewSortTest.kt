package app.milkbank.ui.admin

import app.milkbank.api.OverviewRowDto
import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewSortTest {
    private fun row(id: Int, bed: String, balance: Int, days: Double?, status: String) = OverviewRowDto(
        patientId = id, babyId = "B$id", bed = bed, balance = balance, dailyAvg = days?.let { 2.0 },
        daysLeft = days, status = status, pendingPacks = 0,
    )

    private fun ids(rows: List<OverviewRowDto>) = rows.map { it.patientId }

    @Test fun sortOverview_statusOrder() {
        val rows = listOf(
            row(1, "A1", 9, 5.0, "enough"),
            row(2, "A2", 2, 1.0, "low"),
            row(3, "A3", 0, 0.0, "empty"),
            row(4, "A4", 3, 0.5, "low"),
            row(5, "A5", 8, 4.0, "enough"),
        )
        assertEquals(listOf(3, 4, 2, 5, 1), ids(sortOverview(rows, OverviewSort.STATUS, false)))
    }

    @Test fun sortOverview_nullDaysLast() {
        val rows = listOf(
            row(1, "A1", 9, null, "enough"),
            row(2, "A2", 9, 6.0, "enough"),
            row(3, "A3", 9, null, "enough"),
            row(4, "A0", 9, null, "enough"),
        )
        // null days_left goes last within the status; ties fall back to bed order.
        assertEquals(listOf(2, 4, 1, 3), ids(sortOverview(rows, OverviewSort.STATUS, false)))
    }

    @Test fun sortOverview_balance() {
        val rows = listOf(
            row(1, "A3", 5, 2.0, "enough"),
            row(2, "A1", 2, 2.0, "low"),
            row(3, "A2", 5, 2.0, "enough"),
            row(4, "A4", 0, 0.0, "empty"),
        )
        assertEquals(listOf(4, 2, 3, 1), ids(sortOverview(rows, OverviewSort.BALANCE, false)))
    }

    @Test fun sortOverview_bedKeepsServerOrder() {
        val rows = listOf(row(3, "A3", 1, 1.0, "low"), row(1, "A1", 9, 5.0, "enough"), row(2, "A2", 0, 0.0, "empty"))
        assertEquals(listOf(3, 1, 2), ids(sortOverview(rows, OverviewSort.BED, false)))
    }

    @Test fun sortOverview_lowOnlyKeepsEmpty() {
        val rows = listOf(
            row(1, "A1", 9, 5.0, "enough"),
            row(2, "A2", 2, 1.0, "low"),
            row(3, "A3", 0, 0.0, "empty"),
        )
        assertEquals(listOf(3, 2), ids(sortOverview(rows, OverviewSort.STATUS, true)))
        assertEquals(listOf(2, 3), ids(sortOverview(rows, OverviewSort.BED, true)))
    }
}
