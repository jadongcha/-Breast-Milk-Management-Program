package app.milkbank.ui.login

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.Route
import app.milkbank.ui.components.IdleTextField
import app.milkbank.ui.permission.errorToShow
import app.milkbank.ui.permission.rememberLocalNetworkPermission

@Composable
fun LoginScreen(viewModel: LoginViewModel, onNavigate: (Route) -> Unit, onRegisterAgain: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    // The permission notice (with its button) sits in the app-wide strip; here only drop the misleading network error.
    val permissionMissing = rememberLocalNetworkPermission().missing

    LaunchedEffect(state.next) {
        state.next?.let {
            onNavigate(it)
            viewModel.onNavigated()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.menu_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 56.dp),
                    text = { Text(stringResource(R.string.register_again)) },
                    onClick = {
                        menuOpen = false
                        onRegisterAgain()
                    },
                )
            }
        }
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
                stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            IdleTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = stringResource(R.string.username_label),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
            )
            IdleTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(R.string.password_label),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
                isError = state.error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            )
            errorToShow(state.error, permissionMissing)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.login_button))
            }
        }
    }
}
