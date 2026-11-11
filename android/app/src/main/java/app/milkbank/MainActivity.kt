package app.milkbank

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import app.milkbank.ui.AppNavHost
import app.milkbank.ui.GuardianHome
import app.milkbank.ui.LocalOnline
import app.milkbank.ui.LocalIdleTouch
import app.milkbank.ui.Login
import app.milkbank.ui.Register
import app.milkbank.ui.configClearedNeedsRegister
import app.milkbank.ui.idleTouch
import app.milkbank.ui.MilkbankTheme
import app.milkbank.ui.OfflineBanner
import app.milkbank.ui.Password
import app.milkbank.ui.navigateClearingStack
import app.milkbank.ui.permission.LocalNetworkPermissionNotice
import app.milkbank.ui.permission.localNetworkPermissionRequired
import app.milkbank.ui.permission.rememberLocalNetworkPermission
import app.milkbank.ui.permission.shouldRequestAtStart
import app.milkbank.ui.needsLoginRedirect
import app.milkbank.ui.startDestination
import app.milkbank.ui.stationModeRedirect
import app.milkbank.ui.stationReleased
import app.milkbank.station.StationConfig
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Both stored configs, read: "not loaded yet" (null holder) differs from "loaded, nothing registered". */
private class LoadedConfig(val config: ServerConfig?, val station: StationConfig?)

class MainActivity : ComponentActivity() {
    private val container get() = (application as MilkbankApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37 forces edge-to-edge: light theme, so dark system-bar icons (not the dark-mode default).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x33, 0, 0, 0)),
        )
        super.onCreate(savedInstanceState)
        // Baby IDs and stock are patient data: no screenshots / recents thumbnails in release builds.
        // Debug builds keep screenshots for development and end-to-end checks.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    container.idleWatcher.check()
                    container.guardianIdle.check()
                    delay(1000)
                }
            }
        }
        setContent {
            MilkbankTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.connection.start()
    }

    override fun onResume() {
        super.onResume()
        container.idleWatcher.check()
        container.guardianIdle.check()
    }

    override fun onStop() {
        container.connection.stop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        container.idleWatcher.touch()
        container.guardianIdle.touch()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        container.idleWatcher.touch()
        container.guardianIdle.touch()
        return super.dispatchTouchEvent(ev)
    }

    @Composable
    private fun AppRoot() {
        // Wait for both: station mode decides the start screen (no flash of Login/Patients on a station tablet).
        val loaded by remember {
            combine(container.serverConfigStore.config, container.stationStore.config) { c, st -> LoadedConfig(c, st) }
        }.collectAsStateWithLifecycle(initialValue = null)
        val current = loaded
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        val navController = rememberNavController()
        val session = container.session
        val online by container.connection.online.collectAsStateWithLifecycle()
        val sessionState by session.state.collectAsStateWithLifecycle()
        val logoutReason by session.logoutReason.collectAsStateWithLifecycle()
        val passwordRequired by session.passwordRequired.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val loginAgain = stringResource(R.string.login_again)
        // Decided once, after the stored config has been read.
        val start = remember { startDestination(current.config, sessionState, current.station) }
        // A station tablet shows the guardian screen only (needs a registered server to be meaningful).
        val stationMode = current.station != null && current.config != null

        var previousConfig by remember { mutableStateOf(current.config) }
        LaunchedEffect(current.config) {
            if (configClearedNeedsRegister(previousConfig, current.config)) {
                navController.navigateClearingStack(Register)
            }
            previousConfig = current.config
        }

        // A new session invalidates any stale logout reason.
        LaunchedEffect(sessionState) {
            if (sessionState != null) session.consumeLogoutReason()
        }
        // Station released (admin dialog): back to the login screen with a clean stack.
        var previousStation by remember { mutableStateOf(current.station) }
        LaunchedEffect(current.station) {
            if (stationReleased(previousStation, current.station, current.config != null)) {
                navController.navigateClearingStack(Login)
            }
            previousStation = current.station
        }
        LaunchedEffect(logoutReason) {
            val reason = logoutReason ?: return@LaunchedEffect
            // Station mode never shows Login (the admin session lives inside the release dialog only).
            if (stationMode) {
                session.consumeLogoutReason()
                return@LaunchedEffect
            }
            navController.navigateClearingStack(Login)
            session.consumeLogoutReason()
            if (reason == LogoutReason.EXPIRED || reason == LogoutReason.IDLE) {
                scope.launch { snackbar.showSnackbar(loginAgain) }
            }
        }
        // Process death restores the back stack but not the in-memory session.
        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        val routeIsPublic = destination == null ||
            destination.hasRoute<Register>() || destination.hasRoute<Login>()
        val routeIsGuardian = destination == null || destination.hasRoute<GuardianHome>()
        LaunchedEffect(sessionState, routeIsPublic, current.config, stationMode) {
            if (!stationMode && needsLoginRedirect(sessionState, current.config != null, routeIsPublic)) {
                navController.navigateClearingStack(Login)
            }
        }
        // Also after process death: a restored staff back stack on a station tablet goes to the guardian screen.
        LaunchedEffect(stationMode, routeIsGuardian) {
            if (stationModeRedirect(stationMode, routeIsGuardian)) navController.navigateClearingStack(GuardianHome)
        }
        LaunchedEffect(passwordRequired, stationMode) {
            if (passwordRequired) {
                if (!stationMode) navController.navigateClearingStack(Password)
                session.consumePasswordRequired()
            }
        }

        // A registered server needs local network access before any contact (also after an OS upgrade or a revoke).
        val localNetwork = rememberLocalNetworkPermission()
        LaunchedEffect(current.config != null, localNetwork.granted) {
            if (shouldRequestAtStart(
                    required = localNetworkPermissionRequired(android.os.Build.VERSION.SDK_INT),
                    granted = localNetwork.granted,
                    serverRegistered = current.config != null,
                    askedBefore = localNetwork.askedBefore,
                )
            ) localNetwork.request()
        }

        CompositionLocalProvider(
            LocalOnline provides online,
            LocalIdleTouch provides { container.idleWatcher.touch(); container.guardianIdle.touch() },
        ) {
            // One place for the system bars / camera cutout: banners and screens below never draw under them.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).idleTouch()) {
                // The register screen shows its own notice.
                if (current.config != null && !destination.let { it != null && it.hasRoute<Register>() }) {
                    LocalNetworkPermissionNotice(localNetwork)
                }
                if (!online && current.config != null && localNetwork.granted) OfflineBanner() // no server yet: nothing to be offline from
                Box(Modifier.weight(1f)) {
                    AppNavHost(navController, start)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
