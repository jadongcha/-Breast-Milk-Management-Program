package app.milkbank.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.milkbank.ui.MilkbankColors

/** Each value as a share of the largest one (0..1); all zeros stay zero. */
fun barFractions(values: List<Int>): List<Float> {
    val max = values.maxOrNull() ?: return emptyList()
    return if (max <= 0) values.map { 0f } else values.map { it.toFloat() / max }
}

/** Keeps every [every]th label (the first, then each Nth) and blanks the rest; short lists are left alone. */
fun thinLabels(labels: List<String>, every: Int): List<String> =
    if (labels.size <= 10 || every <= 1) labels else labels.mapIndexed { i, l -> if (i % every == 0) l else "" }

private val MinColumnWidth = 26.dp

/** One tappable bar per value; [selected] is highlighted. The whole column is the tap target. */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    descriptions: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fractions = barFractions(values)
    val shown = thinLabels(labels, 5)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Wide enough columns share the width; with many bars the chart scrolls instead of squeezing them.
        val gap = 2.dp
        val fit = (maxWidth - gap * (fractions.size - 1).coerceAtLeast(0)) / fractions.size.coerceAtLeast(1)
        val colWidth = if (fit < MinColumnWidth) MinColumnWidth else fit
        Row(
            Modifier.horizontalScroll(rememberScrollState()).height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
        fractions.forEachIndexed { i, f ->
            Column(
                Modifier.width(colWidth).fillMaxHeight()
                    .clickable { onSelect(i) }
                    .semantics { contentDescription = descriptions.getOrElse(i) { "" } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(values[i].toString(), style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(f.coerceAtLeast(0.02f))
                            .background(
                                if (i == selected) MilkbankColors.Primary else MilkbankColors.Primary.copy(alpha = 0.45f),
                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                            ),
                    )
                }
                Text(
                    shown.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                )
            }
        }
        }
    }
}
