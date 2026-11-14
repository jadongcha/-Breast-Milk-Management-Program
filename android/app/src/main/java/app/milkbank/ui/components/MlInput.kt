package app.milkbank.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.milkbank.R

const val ML_MIN = 1
const val ML_MAX = 20000

sealed interface MlParse {
    data object Empty : MlParse
    data class Valid(val ml: Int) : MlParse
    data object Invalid : MlParse
}

/** Blank means "no volume entered"; otherwise only plain digits within [ML_MIN]..[ML_MAX] are accepted. */
fun parseMl(text: String): MlParse {
    val t = text.trim()
    if (t.isEmpty()) return MlParse.Empty
    if (t.length > 6 || !t.all { it in '0'..'9' }) return MlParse.Invalid
    val n = t.toInt()
    return if (n in ML_MIN..ML_MAX) MlParse.Valid(n) else MlParse.Invalid
}

/** The value to send: null for empty or invalid input. */
fun MlParse.valueOrNull(): Int? = (this as? MlParse.Valid)?.ml

/** Optional volume field under the pack count; shows the range error under the field when invalid. */
@Composable
fun MlField(text: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, fieldModifier: Modifier = Modifier) {
    val invalid = parseMl(text) == MlParse.Invalid
    Column(modifier) {
        IdleTextField(
            text, onChange, label, Modifier.fillMaxWidth().then(fieldModifier),
            enabled = enabled, isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (invalid) {
            Text(stringResource(R.string.ml_range_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "3팩 · 240mL", or "3팩" without a volume. */
@Composable
fun packsWithMl(count: Int, ml: Int?): String =
    if (ml == null) stringResource(R.string.packs, count) else stringResource(R.string.packs_ml, count, ml)
