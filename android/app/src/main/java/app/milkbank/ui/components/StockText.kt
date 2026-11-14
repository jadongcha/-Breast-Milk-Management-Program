package app.milkbank.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.milkbank.R

/** The wording pieces for stock and record lines, so the rules below can be tested without Android resources. */
class StockFormats(
    val packs: (Int) -> String,
    val withMl: (packs: Int, ml: Int) -> String,
    val unknownSuffix: (unknown: Int) -> String,
    val allUnknown: (packs: Int) -> String,
    val recordPartial: (count: Int, ml: Int, unknown: Int) -> String,
)

/**
 * Stock of a baby: "4팩 · 340mL", "4팩 · 260mL · 용량 모름 1팩", "2팩 · 용량 모름", and a plain "0팩" when empty.
 * [ml] is the sum of the known volumes, [unknown] the number of packs without a volume.
 */
fun stockTextOf(packs: Int, ml: Int, unknown: Int, f: StockFormats): String = when {
    packs <= 0 -> f.packs(packs)
    unknown <= 0 -> f.withMl(packs, ml)
    unknown >= packs && ml == 0 -> f.allUnknown(packs)
    else -> f.withMl(packs, ml) + f.unknownSuffix(unknown)
}

/**
 * One record line: "3팩 · 240mL", "3팩 · 160mL (용량 모름 1팩)", or "3팩" when no volume is known at all.
 * [ml] is null when none of the record's packs has a known volume.
 */
fun recordMlTextOf(count: Int, ml: Int?, unknown: Int, f: StockFormats): String = when {
    ml == null || unknown >= count -> f.packs(count)
    unknown <= 0 -> f.withMl(count, ml)
    else -> f.recordPartial(count, ml, unknown)
}

@Composable
internal fun rememberStockFormats(): StockFormats {
    val res = LocalContext.current.resources
    return remember(res) {
        StockFormats(
            packs = { res.getString(R.string.packs, it) },
            withMl = { p, m -> res.getString(R.string.stock_with_ml, p, m) },
            unknownSuffix = { res.getString(R.string.stock_unknown_suffix, it) },
            allUnknown = { res.getString(R.string.stock_all_unknown, it) },
            recordPartial = { c, m, u -> res.getString(R.string.record_ml_partial, c, m, u) },
        )
    }
}

@Composable
fun stockText(packs: Int, ml: Int, unknown: Int): String = stockTextOf(packs, ml, unknown, rememberStockFormats())

@Composable
fun recordMlText(count: Int, ml: Int?, unknown: Int): String = recordMlTextOf(count, ml, unknown, rememberStockFormats())
