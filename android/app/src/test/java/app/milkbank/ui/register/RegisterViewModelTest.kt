package app.milkbank.ui.register

import app.milkbank.R
import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import app.milkbank.server.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {
    private val pinA = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val pinB = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    private fun qr(url: String, pin: String) = """{"type":"milkbank-server","v":1,"url":"$url","pin":"$pin"}"""

    private var saved: ServerConfig? = null
    private var sessionEnds = 0
    private var healthOk = true
    private var healthCalls = 0

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private var saveThrows = false

    private val reported = mutableListOf<Throwable>()

    private fun vm(current: ServerConfig? = null) = RegisterViewModel(
        health = {
            healthCalls++
            if (healthOk) ApiResult.Ok(Unit) else ApiResult.Err(ApiError(null, "x"))
        },
        currentConfig = { current },
        save = { if (saveThrows) throw java.io.IOException("disk"); saved = it },
        endSession = { sessionEnds++ },
        onError = { reported += it },
    )

    @Test fun notQr_isError() {
        val v = vm()
        v.onScanned("hello")
        assertEquals(RegisterState.Error(R.string.register_not_qr), v.state.value)
        assertEquals(0, healthCalls)
        assertNull(saved)
    }

    @Test fun wrongPin_isRejectedAndNotSaved() {
        healthOk = false
        val v = vm()
        v.onScanned(qr("https://10.0.0.5:8443", pinA))
        assertEquals(RegisterState.Error(R.string.register_unreachable), v.state.value)
        assertNull(saved)
    }

    @Test fun firstRegistration_saves() {
        val v = vm()
        v.onScanned(qr("https://10.0.0.5:8443", pinA))
        assertEquals(RegisterState.Done, v.state.value)
        assertEquals(ServerConfig("https://10.0.0.5:8443", pinA), saved)
        assertEquals(0, sessionEnds)
    }

    @Test fun samePinDifferentUrl_savesDirectly() {
        val v = vm(ServerConfig("https://a:1", pinA))
        v.onScanned(qr("https://b:2", pinA))
        assertEquals(RegisterState.Done, v.state.value)
        assertEquals(ServerConfig("https://b:2", pinA), saved)
    }

    @Test fun differentPin_confirmSaves_andEndsSession() {
        val v = vm(ServerConfig("https://a:1", pinA))
        v.onScanned(qr("https://b:2", pinB))
        val target = ServerConfig("https://b:2", pinB)
        assertEquals(RegisterState.ConfirmDifferentServer(target), v.state.value)
        assertNull(saved)
        v.confirmDifferentServer()
        assertEquals(RegisterState.Done, v.state.value)
        assertEquals(target, saved)
        assertEquals(1, sessionEnds)
    }

    @Test fun differentPin_dismissDoesNotSave() {
        val v = vm(ServerConfig("https://a:1", pinA))
        v.onScanned(qr("https://b:2", pinB))
        v.dismiss()
        assertEquals(RegisterState.Scanning, v.state.value)
        assertNull(saved)
        assertEquals(0, sessionEnds)
    }

    @Test fun scansWhileNotScanning_areIgnored() {
        val v = vm(ServerConfig("https://a:1", pinA))
        v.onScanned(qr("https://b:2", pinB)) // -> Confirm
        v.onScanned(qr("https://c:3", pinA))
        assertTrue(v.state.value is RegisterState.ConfirmDifferentServer)
        assertEquals(1, healthCalls)
    }

    @Test fun errorThenDismiss_allowsRescan() {
        healthOk = false
        val v = vm()
        v.onScanned(qr("https://a:1", pinA))
        v.dismiss()
        healthOk = true
        v.onScanned(qr("https://a:1", pinA))
        assertEquals(RegisterState.Done, v.state.value)
    }

    @Test fun saveThrows_becomesErrorNotStuck() {
        saveThrows = true
        val v = vm()
        v.onScanned(qr("https://a:1", pinA))
        assertEquals(RegisterState.Error(R.string.register_save_failed), v.state.value)
        assertNull(saved)
    }

    @Test fun confirmSaveThrows_becomesError() {
        saveThrows = true
        val v = vm(ServerConfig("https://a:1", pinA))
        v.onScanned(qr("https://b:2", pinB))
        v.confirmDifferentServer()
        assertEquals(RegisterState.Error(R.string.register_save_failed), v.state.value)
        assertEquals(0, sessionEnds)
    }

    @Test fun confirmFromNonConfirmState_isIgnored() {
        val v = vm()
        v.confirmDifferentServer()
        assertEquals(RegisterState.Scanning, v.state.value)
        assertNull(saved)
        assertEquals(0, sessionEnds)
    }

    @Test fun saveThrows_reportsExceptionToOnError() {
        saveThrows = true
        val v = vm()
        v.onScanned(qr("https://a:1", pinA))
        assertEquals(1, reported.size)
        assertTrue(reported[0] is java.io.IOException)
    }

    @Test fun confirmSaveThrows_reportsExceptionToOnError() {
        saveThrows = true
        val v = vm(ServerConfig("https://a:1", pinA))
        v.onScanned(qr("https://b:2", pinB))
        v.confirmDifferentServer()
        assertEquals(1, reported.size)
    }
}
