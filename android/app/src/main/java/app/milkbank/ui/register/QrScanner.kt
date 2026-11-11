package app.milkbank.ui.register

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera preview that reports the first QR code seen. After a result, frames are ignored until
 * [enabled] turns true again. The camera is unbound and the ML Kit scanner closed on leaving.
 * [onError] is called (on the main thread) when the camera cannot be started.
 */
@Composable
fun QrScanner(
    enabled: Boolean,
    onQr: (String) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQr by rememberUpdatedState(onQr)
    val currentOnError by rememberUpdatedState(onError)
    val armed = remember { AtomicBoolean(enabled) }
    LaunchedEffect(enabled) { armed.set(enabled) }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        try {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (disposed) return@addListener
                try {
                    val p = providerFuture.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        try {
                            val media = proxy.image
                            if (media == null || !armed.get()) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener(mainExecutor) { codes ->
                                    val raw = codes.firstNotNullOfOrNull { it.rawValue }
                                    if (raw != null && armed.compareAndSet(true, false)) currentOnQr(raw)
                                }
                                .addOnCompleteListener { proxy.close() }
                        } catch (e: Exception) {
                            proxy.close()
                        }
                    }
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    currentOnError()
                }
            }, mainExecutor)
        } catch (e: Exception) {
            currentOnError()
        }

        onDispose {
            disposed = true
            armed.set(false)
            provider?.unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
