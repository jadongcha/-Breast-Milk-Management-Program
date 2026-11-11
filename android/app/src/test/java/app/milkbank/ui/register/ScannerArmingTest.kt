package app.milkbank.ui.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerArmingTest {
    @Test fun armedOnlyWhenScanningAndLocalNetworkGranted() {
        assertTrue(scannerArmed(RegisterState.Scanning, localNetworkGranted = true))
    }

    @Test fun notArmedBeforeLocalNetworkPermission() {
        // A QR seen now must not be consumed and dropped; the scanner re-arms when the permission arrives.
        assertFalse(scannerArmed(RegisterState.Scanning, localNetworkGranted = false))
    }

    @Test fun notArmedWhileCheckingOrAfterError() {
        assertFalse(scannerArmed(RegisterState.Checking, localNetworkGranted = true))
        assertFalse(scannerArmed(RegisterState.Error(1), localNetworkGranted = true))
        assertFalse(scannerArmed(RegisterState.Done, localNetworkGranted = true))
    }
}
