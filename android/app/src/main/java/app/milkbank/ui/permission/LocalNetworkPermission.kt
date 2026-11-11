package app.milkbank.ui.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.milkbank.R

/** Android 17 (API 37): sockets to local-network addresses need this runtime permission ("근처 기기"). */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

fun localNetworkPermissionRequired(sdkInt: Int): Boolean = sdkInt >= 37

enum class PermissionUi { GRANTED, ASK, SETTINGS }

/** SETTINGS once we asked and the system will not show its dialog again (denied, no rationale). */
fun permissionUi(granted: Boolean, shouldShowRationale: Boolean, askedBefore: Boolean): PermissionUi = when {
    granted -> PermissionUi.GRANTED
    askedBefore && !shouldShowRationale -> PermissionUi.SETTINGS
    else -> PermissionUi.ASK
}

/** A network error caused by the missing permission is explained by the permission message instead. */
fun errorToShow(error: String?, permissionMissing: Boolean): String? = if (permissionMissing) null else error

fun shouldRequestAtStart(required: Boolean, granted: Boolean, serverRegistered: Boolean, askedBefore: Boolean): Boolean =
    required && !granted && serverRegistered && !askedBefore

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class LocalNetworkPermissionState(
    val granted: Boolean,
    val ui: PermissionUi,
    val request: () -> Unit,
    val openSettings: () -> Unit,
    val askedBefore: Boolean,
) {
    val missing get() = !granted
}

/** Tracks the permission (re-checked on ON_RESUME, e.g. back from Settings) and offers request/settings actions. */
@Composable
fun rememberLocalNetworkPermission(): LocalNetworkPermissionState {
    val context = LocalContext.current
    val required = localNetworkPermissionRequired(Build.VERSION.SDK_INT)
    fun check() = !required ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(check()) }
    var askedBefore by rememberSaveable { mutableStateOf(false) }
    var rationale by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedBefore = true
        val activity = context.findActivity()
        rationale = !ok && activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, LOCAL_NETWORK_PERMISSION)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = check()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return LocalNetworkPermissionState(
        granted = granted,
        ui = permissionUi(granted, rationale, askedBefore),
        request = { launcher.launch(LOCAL_NETWORK_PERMISSION) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        askedBefore = askedBefore,
    )
}

/** Message plus [권한 다시 요청] / [설정에서 권한 허용]. Shown only while the permission is missing. */
@Composable
fun LocalNetworkPermissionNotice(state: LocalNetworkPermissionState, modifier: Modifier = Modifier) {
    if (state.granted) return
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.local_network_permission_needed),
            color = MaterialTheme.colorScheme.error,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        val settings = state.ui == PermissionUi.SETTINGS
        Button(
            onClick = if (settings) state.openSettings else state.request,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(
                stringResource(if (settings) R.string.local_network_permission_settings else R.string.local_network_permission_request),
                fontSize = 18.sp,
            )
        }
    }
}
