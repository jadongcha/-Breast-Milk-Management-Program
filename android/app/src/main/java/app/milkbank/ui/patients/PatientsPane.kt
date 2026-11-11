package app.milkbank.ui.patients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import app.milkbank.AppContainer
import app.milkbank.R
import app.milkbank.ui.logUnexpected
import app.milkbank.core.SystemClock
import app.milkbank.ui.MilkbankColors
import app.milkbank.ui.Password
import app.milkbank.ui.PatientDetail
import app.milkbank.ui.PatientForm
import app.milkbank.ui.GuardianHome
import app.milkbank.ui.Staff
import app.milkbank.ui.Stations
import app.milkbank.ui.navigateClearingStack
import app.milkbank.ui.station.StationSetupDialog
import app.milkbank.ui.station.StationSetupViewModel
import app.milkbank.ui.idleTouch

private const val WIDE_DP = 840

@Composable
fun rememberDetailViewModel(container: AppContainer, id: Int): PatientDetailViewModel {
    val saved = stringResource(R.string.saved)
    val unexpected = stringResource(R.string.network_error)
    val uncertain = stringResource(R.string.save_uncertain)
    return viewModel(
        key = "patient-detail-$id",
        factory = viewModelFactory {
            initializer {
                PatientDetailViewModel(
                    patientId = id,
                    patientRepo = { container.patients },
                    stockRepo = { container.stock },
                    banner = container.resultBanner,
                    session = container.session,
                    clock = SystemClock,
                    savedMessage = saved,
                    unexpectedError = unexpected,
                    saveUncertainMessage = uncertain,
                    onError = logUnexpected("patient detail action failed"),
                )
            }
        },
    )
}

/** Home route: top bar + list; two panes (list left, detail right) from 840dp, else a separate detail route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsPane(container: AppContainer, navController: NavHostController) {
    val unexpected = stringResource(R.string.network_error)
    val listVm: PatientListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PatientListViewModel({ container.patients }, container.resultBanner, unexpectedError = unexpected,
                    onError = logUnexpected("patient list failed"))
            }
        },
    )
    val intakeConfirm = stringResource(R.string.kind_intake_confirm)
    val pendingUncertain = stringResource(R.string.pending_uncertain)
    val pendingVm: PendingViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PendingViewModel(
                    { container.submissions }, container.resultBanner, SystemClock, intakeConfirm,
                    pendingUncertain, unexpected, logUnexpected("pending table failed"),
                )
            }
        },
    )
    val session by container.session.state.collectAsStateWithLifecycle()
    val staff = session?.staff
    val isAdmin = staff?.role == "admin"
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }

    // Not gated on isAdmin: the session ends during setup, and the dialog must stay to report Done.
    if (setupOpen) {
        val setupVm: StationSetupViewModel = viewModel(
            factory = viewModelFactory {
                initializer {
                    StationSetupViewModel(
                        { container.stations }, { container.auth }, container.stationStore, container.session, unexpected,
                        logUnexpected("station setup failed"),
                    )
                }
            },
        )
        StationSetupDialog(
            setupVm,
            onDismiss = { setupOpen = false },
            onDone = {
                setupOpen = false
                navController.navigateClearingStack(GuardianHome)
            },
        )
    }

    Scaffold(

        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(stringResource(R.string.home_title), fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    staff?.let { Text(it.name, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium) }
                    Box {
                        val moreDesc = stringResource(R.string.menu_more)
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.padding(end = 4.dp).size(56.dp).semantics { contentDescription = moreDesc },
                        ) {
                            // The glyph is decoration: TalkBack reads only "더보기".
                            Text("⋮", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineMedium)
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }, modifier = Modifier.idleTouch()) {
                            DropdownMenuItem(
                                modifier = Modifier.heightIn(min = 56.dp),
                                text = { Text(stringResource(R.string.password_change_title)) },
                                onClick = { menuOpen = false; navController.navigate(Password) },
                            )
                            if (isAdmin) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    modifier = Modifier.heightIn(min = 56.dp),
                                    text = { Text(stringResource(R.string.menu_admin_patient_form)) },
                                    onClick = { menuOpen = false; navController.navigate(PatientForm(null)) },
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.heightIn(min = 56.dp),
                                    text = { Text(stringResource(R.string.menu_admin_staff)) },
                                    onClick = { menuOpen = false; navController.navigate(Staff) },
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.heightIn(min = 56.dp),
                                    text = { Text(stringResource(R.string.stations_menu)) },
                                    onClick = { menuOpen = false; navController.navigate(Stations) },
                                )
                                DropdownMenuItem(
                                    modifier = Modifier.heightIn(min = 56.dp),
                                    text = { Text(stringResource(R.string.station_use_menu)) },
                                    onClick = { menuOpen = false; setupOpen = true },
                                )
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                modifier = Modifier.heightIn(min = 56.dp),
                                text = { Text(stringResource(R.string.logout)) },
                                onClick = {
                                    menuOpen = false
                                    container.auth.logout()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val total = maxWidth
            if (total >= WIDE_DP.dp) {
                Row(Modifier.fillMaxSize()) {
                    PatientListContent(
                        listVm,
                        pendingVm,
                        onSelect = { selectedId = it.id },
                        modifier = Modifier.width(total * 0.4f).fillMaxHeight(),
                    )
                    Box(Modifier.weight(1f).fillMaxHeight().background(MilkbankColors.Background)) {
                        val id = selectedId
                        if (id == null) {
                            Text(
                                stringResource(R.string.select_patient_hint),
                                Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            key(id) { // dialog/snackbar state must not carry over between babies
                                val detailVm = rememberDetailViewModel(container, id)
                                LifecycleResumeEffect(id) { detailVm.refresh(); onPauseOrDispose { } }
                                PatientDetailScreen(
                                    detailVm,
                                    onEdit = if (isAdmin) { pid: Int -> navController.navigate(PatientForm(pid)) } else null,
                                )
                            }
                        }
                    }
                }
            } else {
                PatientListContent(listVm, pendingVm, onSelect = { navController.navigate(PatientDetail(it.id)) { launchSingleTop = true } })
            }
        }
    }
}

/** Narrow-screen detail route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailRoute(container: AppContainer, id: Int, navController: NavHostController) {
    val vm = rememberDetailViewModel(container, id)
    val session by container.session.state.collectAsStateWithLifecycle()
    val isAdmin = session?.staff?.role == "admin"
    LifecycleResumeEffect(id) { vm.refresh(); onPauseOrDispose { } }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    androidx.compose.material3.TextButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.padding(start = 4.dp).heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.back_with_arrow)) }
                },
            )
        },
    ) { padding ->
        PatientDetailScreen(
            vm,
            onEdit = if (isAdmin) { pid: Int -> navController.navigate(PatientForm(pid)) } else null,
            modifier = Modifier.padding(padding),
        )
    }
}

