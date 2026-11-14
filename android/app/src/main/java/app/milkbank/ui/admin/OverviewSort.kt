package app.milkbank.ui.admin

import app.milkbank.api.OverviewRowDto

enum class OverviewSort { STATUS, BED, BALANCE }

private fun statusRank(status: String): Int = when (status) {
    "empty" -> 0
    "low" -> 1
    else -> 2
}

/**
 * STATUS: empty, low, enough; inside a status the fewest days left first (unknown last), then bed.
 * BED: the server's order (already by bed). BALANCE: fewest packs first, then bed.
 * [lowOnly] keeps only "low" and "empty" rows.
 */
fun sortOverview(rows: List<OverviewRowDto>, sort: OverviewSort, lowOnly: Boolean): List<OverviewRowDto> {
    val shown = if (lowOnly) rows.filter { it.status == "low" || it.status == "empty" } else rows
    return when (sort) {
        OverviewSort.BED -> shown
        OverviewSort.BALANCE -> shown.sortedWith(compareBy<OverviewRowDto> { it.balance }.thenBy { it.bed })
        OverviewSort.STATUS -> shown.sortedWith(
            compareBy<OverviewRowDto> { statusRank(it.status) }
                .thenBy { it.daysLeft == null }
                .thenBy { it.daysLeft }
                .thenBy { it.bed },
        )
    }
}
