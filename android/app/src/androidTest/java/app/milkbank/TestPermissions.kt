package app.milkbank

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants the Android 17 local-network runtime permission to the app under test, so the permission gate does
 * not cover the screens being tested. Done here (not in the Gradle install options) so normal installs still
 * show the gate.
 */
fun grantLocalNetworkPermission() {
    if (Build.VERSION.SDK_INT < 37) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        "android.permission.ACCESS_LOCAL_NETWORK",
    )
}
