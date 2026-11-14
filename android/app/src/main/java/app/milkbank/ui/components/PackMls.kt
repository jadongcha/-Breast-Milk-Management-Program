package app.milkbank.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.milkbank.R

const val PACK_ML_MIN = 1
const val PACK_ML_MAX = 1000

sealed interface ParsedPackMl {
    data object Empty : ParsedPackMl
    data class Valid(val ml: Int) : ParsedPackMl
    data object Invalid : ParsedPackMl
}

/** Blank means "volume unknown"; otherwise only plain digits within [PACK_ML_MIN]..[PACK_ML_MAX] are accepted. */
fun parsePackMl(text: String): ParsedPackMl {
    val t = text.trim()
    if (t.isEmpty()) return ParsedPackMl.Empty
    if (t.length > 4 || !t.all { it in '0'..'9' }) return ParsedPackMl.Invalid
    val n = t.toInt()
    return if (n in PACK_ML_MIN..PACK_ML_MAX) ParsedPackMl.Valid(n) else ParsedPackMl.Invalid
}

/**
 * What the user typed for each pack plus the "same volume for all" field.
 * Plain strings so the fields keep exactly what was typed; [values] is what to send.
 */
data class PackMlsState(val texts: List<String>, val allText: String = "") {
    /** Follows the pack count: keeps the leading fields, new ones are blank. */
    fun resize(count: Int): PackMlsState =
        copy(texts = List(count) { texts.getOrElse(it) { "" } })

    fun setAt(i: Int, text: String): PackMlsState =
        copy(texts = texts.mapIndexed { idx, old -> if (idx == i) text else old })

    /** A valid value fills every field; an empty or invalid one only changes the "all" field. */
    fun setAll(text: String): PackMlsState {
        val parsed = parsePackMl(text)
        return if (parsed is ParsedPackMl.Valid) copy(texts = texts.map { parsed.ml.toString() }, allText = text)
        else copy(allText = text)
    }

    /** One entry per pack (null = unknown), or null when any field is invalid. */
    val values: List<Int?>?
        get() {
            val out = ArrayList<Int?>(texts.size)
            for (t in texts) {
                when (val p = parsePackMl(t)) {
                    ParsedPackMl.Empty -> out += null
                    is ParsedPackMl.Valid -> out += p.ml
                    ParsedPackMl.Invalid -> return null
                }
            }
            return out
        }

    val invalidIndexes: Set<Int>
        get() = texts.indices.filter { parsePackMl(texts[it]) == ParsedPackMl.Invalid }.toSet()

    val allInvalid: Boolean get() = parsePackMl(allText) == ParsedPackMl.Invalid

    companion object {
        fun empty(count: Int) = PackMlsState(List(count) { "" })
        fun from(values: List<Int?>) = PackMlsState(values.map { it?.toString() ?: "" })
    }
}

/** "Same volume for all" on top, then one field per pack; out-of-range input shows the error under its field. */
@Composable
fun PackMlFields(state: PackMlsState, onChange: (PackMlsState) -> Unit, minHeight: Dp, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier) {
        PackMlField(
            state.allText, { onChange(state.setAll(it)) }, stringResource(R.string.pack_ml_all_label),
            invalid = state.allInvalid, minHeight = minHeight, enabled = enabled,
        )
        Text(
            stringResource(R.string.pack_ml_hint), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        state.texts.forEachIndexed { i, text ->
            PackMlField(
                text, { onChange(state.setAt(i, it)) }, stringResource(R.string.pack_ml_label, i + 1),
                invalid = i in state.invalidIndexes, minHeight = minHeight, enabled = enabled,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PackMlField(
    text: String, onChange: (String) -> Unit, label: String, invalid: Boolean, minHeight: Dp, enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        IdleTextField(
            text, onChange, label, Modifier.fillMaxWidth().heightIn(min = minHeight),
            enabled = enabled, isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (invalid) {
            Text(stringResource(R.string.pack_ml_range_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Known volumes added up ([ml] is null when none is known) and how many packs have no volume. */
data class PackMlSum(val ml: Int?, val unknown: Int)

fun sumPackMls(values: List<Int?>): PackMlSum {
    val known = values.filterNotNull()
    return PackMlSum(if (known.isEmpty()) null else known.sum(), values.size - known.size)
}
