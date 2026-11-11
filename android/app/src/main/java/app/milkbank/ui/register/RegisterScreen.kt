package app.milkbank.ui.register

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.milkbank.R
import app.milkbank.ui.IdleAwareDialog
import app.milkbank.ui.permission.LocalNetworkPermissionNotice
import app.milkbank.ui.permission.rememberLocalNetworkPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ButtonHeight = 56.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun RegisterScreen(viewModel: RegisterViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var cameraAsked by remember { mutableStateOf(granted) }
    val localNetwork = rememberLocalNetworkPermission()
    var cameraFailed by remember { mutableStateOf(false) }
    var testFileMissing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        cameraAsked = true
        // Denied and the system will not ask again: only the Settings screen can help.
        val activity = context.findActivity()
        permanentlyDenied = !ok && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    // Back from Settings: pick up a permission granted there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    // One system dialog at a time: ask for local network access once the camera question is settled.
    LaunchedEffect(cameraAsked) {
        if (cameraAsked && !localNetwork.granted && !localNetwork.askedBefore) localNetwork.request()
    }
    LaunchedEffect(state) { if (state == RegisterState.Done) onDone() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.register_prompt),
                modifier = Modifier.weight(1f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (granted) {
                QrScanner(
                    enabled = state == RegisterState.Scanning,
                    onQr = { raw -> if (localNetwork.granted) viewModel.onScanned(raw) },
                    onError = { cameraFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.camera_permission_needed), fontSize = 20.sp, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.camera_permission_rationale),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = {
                            if (permanentlyDenied) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            } else {
                                launcher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.heightIn(min = ButtonHeight),
                    ) {
                        Text(
                            stringResource(if (permanentlyDenied) R.string.camera_permission_settings else R.string.camera_permission_request),
                            fontSize = 18.sp,
                        )
                    }
                }
            }
            if (granted && cameraFailed) {
                Text(
                    stringResource(R.string.camera_start_failed),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            if (state == RegisterState.Checking) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.register_checking), fontSize = 20.sp)
                    }
                }
            }
        }

        // Without the permission the server check would only time out: say why instead.
        LocalNetworkPermissionNotice(localNetwork, Modifier.padding(0.dp))

        (state as? RegisterState.Error)?.let { error ->
            Text(
                stringResource(error.messageRes),
                color = MaterialTheme.colorScheme.error,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = viewModel::dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight)) {
                Text(stringResource(R.string.register_rescan), fontSize = 18.sp)
            }
        }

        if (debuggable) {
            if (testFileMissing) {
                Text(
                    stringResource(R.string.register_test_file_missing),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                enabled = state != RegisterState.Checking && localNetwork.granted,
                onClick = {
                    scope.launch {
                        val raw = withContext(Dispatchers.IO) { readTestQr(context.getExternalFilesDir(null)) }
                        if (raw == null) {
                            testFileMissing = true
                        } else {
                            testFileMissing = false
                            viewModel.onScanned(raw)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight).navigationBarsPadding(),
            ) {
                Text(stringResource(R.string.register_test_button), fontSize = 18.sp)
            }
        }
    }

    if (state is RegisterState.ConfirmDifferentServer) {
        // Dismissing (outside tap / back) means cancel; only [바꾸기] switches servers.
        IdleAwareDialog(onDismissRequest = viewModel::dismiss) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.register_pin_changed), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = viewModel::dismiss,
                            modifier = Modifier.weight(1f).heightIn(min = ButtonHeight),
                        ) { Text(stringResource(R.string.cancel), fontSize = 18.sp) }
                        Button(
                            onClick = viewModel::confirmDifferentServer,
                            modifier = Modifier.weight(1f).heightIn(min = ButtonHeight),
                        ) { Text(stringResource(R.string.register_change_server), fontSize = 18.sp) }
                    }
                }
            }
        }
    }
}
