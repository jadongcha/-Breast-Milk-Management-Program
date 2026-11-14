package app.milkbank.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import app.milkbank.ui.guardian.GuardianGeneralRoute
import app.milkbank.ui.guardian.GuardianHomeRoute
import app.milkbank.ui.station.StationsScreen
import app.milkbank.ui.station.StationsViewModel
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
import app.milkbank.core.SystemClock
import app.milkbank.ui.admin.PatientFormScreen
import app.milkbank.ui.admin.PatientFormViewModel
import app.milkbank.ui.admin.OverviewScreen
import app.milkbank.ui.admin.OverviewViewModel
import app.milkbank.ui.admin.SettingsScreen
import app.milkbank.ui.admin.SettingsViewModel
import app.milkbank.ui.admin.StaffScreen
import app.milkbank.ui.admin.StaffViewModel
import app.milkbank.ui.login.LoginScreen
import app.milkbank.ui.login.LoginViewModel
import app.milkbank.ui.login.PasswordScreen
import app.milkbank.ui.login.PasswordViewModel
import app.milkbank.session.LogoutReason
import app.milkbank.ui.patients.PatientDetailRoute
import app.milkbank.ui.patients.PatientsPane
import app.milkbank.ui.register.RegisterScreen
import androidx.navigation.toRoute
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
                            onError = logUnexpected("register failed"),
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
                    initializer { LoginViewModel({ container.auth }, container.session, unexpected, logUnexpected("login failed")) }
                },
            )
            LoginScreen(
                vm,
                onNavigate = { navController.navigateClearingStack(it) },
                onRegisterAgain = { navController.navigate(Register) },
                onGuardian = { navController.navigate(GuardianGeneral) },
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
                    initializer { PasswordViewModel({ container.auth }, container.session, mismatch, unexpected, logUnexpected("password change failed")) }
                },
            )
            PasswordScreen(
                vm,
                forced = forced,
                onNavigate = { navController.navigateClearingStack(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Patients> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            PatientsPane(container, navController)
        }
        composable<PatientDetail> { entry ->
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            PatientDetailRoute(container, entry.toRoute<PatientDetail>().id, navController)
        }
        composable<PatientForm> { entry ->
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val id = entry.toRoute<PatientForm>().id
            val unexpected = stringResource(R.string.network_error)
            val vm: PatientFormViewModel = viewModel(
                key = "patient-form-$id",
                factory = viewModelFactory {
                    initializer {
                        PatientFormViewModel(
                            id, { container.patients }, container.resultBanner, SystemClock, unexpected,
                            logUnexpected("patient form failed"),
                        )
                    }
                },
            )
            PatientFormScreen(
                vm,
                editing = id != null,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Staff> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val unexpected = stringResource(R.string.network_error)
            val vm: StaffViewModel = viewModel(
                factory = viewModelFactory { initializer { StaffViewModel({ container.staff }, unexpected, logUnexpected("staff action failed")) } },
            )
            StaffScreen(vm, onBack = { navController.popBackStack() })
        }
        composable<Stations> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val unexpected = stringResource(R.string.network_error)
            val vm: StationsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { StationsViewModel({ container.stations }, unexpected, logUnexpected("stations failed")) }
                },
            )
            StationsScreen(vm, onBack = { navController.popBackStack() })
        }
        composable<AdminOverview> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val unexpected = stringResource(R.string.network_error)
            val vm: OverviewViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { OverviewViewModel({ container.admin }, unexpected, logUnexpected("overview failed")) }
                },
            )
            OverviewScreen(
                vm, onBack = { navController.popBackStack() },
                onOpenPatient = { navController.navigate(PatientDetail(it)) },
            )
        }
        composable<AdminSettings> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            val unexpected = stringResource(R.string.network_error)
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            { container.admin }, { container.session.setIdleMinutes(it) }, unexpected,
                            logUnexpected("settings failed"),
                        )
                    }
                },
            )
            SettingsScreen(vm, onBack = { navController.popBackStack() })
        }
        composable<GuardianHome> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            GuardianHomeRoute(container)
        }
        composable<GuardianGeneral> {
            val container = (LocalContext.current.applicationContext as MilkbankApp).container
            // Back to the choice screen with a clean stack: nothing of the parent's session stays behind.
            GuardianGeneralRoute(container, onExit = { navController.navigateClearingStack(Login) })
        }
    }
}
