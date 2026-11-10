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
import app.milkbank.MilkbankApp
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
        composable<Login> { Placeholder("Login") }
        composable<Password> { Placeholder("Password") }
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
