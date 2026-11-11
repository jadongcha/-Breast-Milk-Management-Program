package app.milkbank.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.Route
import app.milkbank.ui.components.IdleTextField

/**
 * [forced] is true on first login: back must not bypass the change, so back (and the cancel
 * button) log out to the login screen instead. Otherwise back simply leaves ([onBack]).
 */
@Composable
fun PasswordScreen(
    viewModel: PasswordViewModel,
    forced: Boolean,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    LaunchedEffect(state.next) {
        state.next?.let {
            onNavigate(it)
            viewModel.onNavigated()
        }
    }
    val leave = { if (!state.loading) { if (forced) viewModel.logout() else onBack() } }
    BackHandler(onBack = leave)

    fun submit() = viewModel.submit(current, new, confirm)
    fun options(ime: ImeAction) =
        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ime)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.password_change_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (forced) Text(stringResource(R.string.password_forced_notice))
            IdleTextField(
                value = current,
                onValueChange = { current = it; viewModel.clearError() },
                label = stringResource(R.string.current_password_label),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = options(ImeAction.Next),
            )
            IdleTextField(
                value = new,
                onValueChange = { new = it; viewModel.clearError() },
                label = stringResource(R.string.new_password_label),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = options(ImeAction.Next),
            )
            IdleTextField(
                value = confirm,
                onValueChange = { confirm = it; viewModel.clearError() },
                label = stringResource(R.string.confirm_password_label),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
                isError = state.error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = options(ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { submit() },
                enabled = !state.loading && current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.password_change_button))
            }
            OutlinedButton(
                onClick = leave,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(if (forced) R.string.logout else R.string.back))
            }
        }
    }
}
