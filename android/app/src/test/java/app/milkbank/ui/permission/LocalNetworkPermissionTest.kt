package app.milkbank.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test fun requiredOnlyFromAndroid17() {
        assertFalse(localNetworkPermissionRequired(36))
        assertTrue(localNetworkPermissionRequired(37))
        assertTrue(localNetworkPermissionRequired(38))
    }

    @Test fun grantedIsGranted() {
        assertEquals(PermissionUi.GRANTED, permissionUi(granted = true, shouldShowRationale = false, askedBefore = true))
    }

    @Test fun neverAsked_asks() {
        assertEquals(PermissionUi.ASK, permissionUi(granted = false, shouldShowRationale = false, askedBefore = false))
    }

    @Test fun deniedOnceWithRationale_asksAgain() {
        assertEquals(PermissionUi.ASK, permissionUi(granted = false, shouldShowRationale = true, askedBefore = true))
    }

    @Test fun deniedWithoutRationaleAfterAsking_goesToSettings() {
        assertEquals(PermissionUi.SETTINGS, permissionUi(granted = false, shouldShowRationale = false, askedBefore = true))
    }

    @Test fun missingPermissionHidesNetworkError() {
        assertNull(errorToShow("서버에 연결할 수 없습니다", permissionMissing = true))
        assertEquals("x", errorToShow("x", permissionMissing = false))
        assertNull(errorToShow(null, permissionMissing = false))
    }

    @Test fun shouldRequestAtStart_onlyWhenServerRegisteredAndMissing() {
        assertTrue(shouldRequestAtStart(required = true, granted = false, serverRegistered = true, askedBefore = false))
        assertFalse(shouldRequestAtStart(required = true, granted = false, serverRegistered = false, askedBefore = false))
        assertFalse(shouldRequestAtStart(required = true, granted = true, serverRegistered = true, askedBefore = false))
        assertFalse(shouldRequestAtStart(required = false, granted = false, serverRegistered = true, askedBefore = false))
        assertFalse(shouldRequestAtStart(required = true, granted = false, serverRegistered = true, askedBefore = true))
    }
}
