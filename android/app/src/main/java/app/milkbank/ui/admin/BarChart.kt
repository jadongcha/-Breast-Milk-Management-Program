package app.milkbank.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    Row(modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        fractions.forEachIndexed { i, f ->
            Column(
                Modifier.weight(1f).fillMaxHeight()
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
                    labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}
