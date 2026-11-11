package app.milkbank

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Wrapper so "not loaded yet" differs from "loaded, no server registered". */
private class LoadedConfig(val config: ServerConfig?)

class MainActivity : ComponentActivity() {
    private val container get() = (application as MilkbankApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    container.idleWatcher.check()
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
    }

    override fun onStop() {
        container.connection.stop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        container.idleWatcher.touch()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        container.idleWatcher.touch()
        return super.dispatchTouchEvent(ev)
    }

    @Composable
    private fun AppRoot() {
        val loaded by remember { container.serverConfigStore.config.map { LoadedConfig(it) } }
            .collectAsStateWithLifecycle(initialValue = null)
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
        val start = remember { startDestination(current.config, sessionState) }

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
        LaunchedEffect(logoutReason) {
            val reason = logoutReason ?: return@LaunchedEffect
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
        LaunchedEffect(sessionState, routeIsPublic, current.config) {
            if (needsLoginRedirect(sessionState, current.config != null, routeIsPublic)) {
                navController.navigateClearingStack(Login)
            }
        }
        LaunchedEffect(passwordRequired) {
            if (passwordRequired) {
                navController.navigateClearingStack(Password)
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
            LocalIdleTouch provides { container.idleWatcher.touch() },
        ) {
            Column(Modifier.fillMaxSize().idleTouch()) {
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
