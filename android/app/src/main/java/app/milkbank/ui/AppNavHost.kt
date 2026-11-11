package app.milkbank.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.milkbank.MilkbankApp
import app.milkbank.R
import app.milkbank.ui.login.LoginScreen
import app.milkbank.ui.login.LoginViewModel
import app.milkbank.ui.login.PasswordScreen
import app.milkbank.ui.login.PasswordViewModel
import app.milkbank.session.LogoutReason
import app.milkbank.ui.register.RegisterScreen
import app.milkbank.ui.register.RegisterViewModel
import kotlinx.coroutines.flow.first

/** Pops everything, then shows [route]. */
fun NavHostController.navigateClearingStack(route: Route) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun AppNavHost(navController: NavHostController, start: Route, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = start, modifier = modifier) {
        // Placeholders: Tasks 7-10 replace these.
        composable<Register> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val vm: RegisterViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        RegisterViewModel(
                            health = { container.auth.health(it) },
                            currentConfig = { container.serverConfigStore.config.first() },
                            save = { container.serverConfigStore.save(it) },
                            endSession = { container.session.end(LogoutReason.USER) },
                        )
                    }
                },
            )
            RegisterScreen(vm, onDone = { navController.navigateClearingStack(Login) })
        }
        composable<Login> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val unexpected = stringResource(R.string.network_error)
            val vm: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { LoginViewModel(container.auth, container.session, unexpected) }
                },
            )
            LoginScreen(
                vm,
                onNavigate = { navController.navigateClearingStack(it) },
                onRegisterAgain = { navController.navigate(Register) },
            )
        }
        composable<Password> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val mismatch = stringResource(R.string.password_mismatch)
            val unexpected = stringResource(R.string.network_error)
            val forced = remember {
                container.session.state.value?.staff?.mustChangePassword == true || container.session.passwordChangeForced
            }
            val vm: PasswordViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { PasswordViewModel(container.auth, container.session, mismatch, unexpected) }
                },
            )
            PasswordScreen(
                vm,
                forced = forced,
                onNavigate = { navController.navigateClearingStack(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Patients> { Placeholder("Patients") }
        composable<PatientDetail> { Placeholder("PatientDetail") }
        composable<PatientForm> { Placeholder("PatientForm") }
        composable<Staff> { Placeholder("Staff") }
    }
}

@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) }
}
