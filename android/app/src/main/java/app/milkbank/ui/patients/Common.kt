package app.milkbank.ui.patients

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.milkbank.R

/** Spec 3.8 card: 12dp corners, thin border, no shadow. */
@Composable
fun MilkCard(
    modifier: Modifier = Modifier,
    container: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = container),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

/** Accepts both stock kinds ("intake") and record kinds ("in"). */
@Composable
fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "intake", "in" -> R.string.kind_intake
        "use" -> R.string.kind_use
        "discard" -> R.string.kind_discard
        "return" -> R.string.kind_return
        else -> R.string.kind_cancel
    },
)

@Composable
fun reasonLabel(reason: String): String = stringResource(
    when (reason) {
        "thawed_leftover" -> R.string.reason_thawed_leftover
        "contaminated" -> R.string.reason_contaminated
        else -> R.string.reason_other
    },
)
