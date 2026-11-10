package app.milkbank.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {
    @Test
    fun failsThenRecovers_every30Seconds() = runTest {
        var ok = false
        var calls = 0
        val m = ConnectionMonitor(backgroundScope, probe = { calls++; ok })
        assertTrue(m.online.value)
        m.start()
        runCurrent()
        assertEquals(1, calls)
        assertFalse(m.online.value)
        advanceTimeBy(29.seconds)
        assertEquals(1, calls)
        ok = true
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, calls)
        assertTrue(m.online.value)
    }

    @Test
    fun startTwice_doesNotStackLoops() = runTest {
        var calls = 0
        val m = ConnectionMonitor(backgroundScope, probe = { calls++; true })
        m.start()
        m.start()
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun noServer_reportsOnlineWithoutProbing_thenProbesWhenRegistered() = runTest {
        var calls = 0
        val key = kotlinx.coroutines.flow.MutableStateFlow<Any?>(null)
        val m = ConnectionMonitor(backgroundScope, serverKey = key, probe = { calls++; false })
        m.start()
        runCurrent()
        assertEquals(0, calls)
        assertTrue(m.online.value)
        key.value = "server1"
        runCurrent()
        assertEquals(1, calls)
        assertFalse(m.online.value)
        key.value = "server2" // repositories changed: probe again at once
        runCurrent()
        assertEquals(2, calls)
        key.value = null // unregistered: back to online
        runCurrent()
        assertTrue(m.online.value)
    }

    @Test
    fun stop_haltsProbing() = runTest {
        var calls = 0
        val m = ConnectionMonitor(backgroundScope, probe = { calls++; true })
        m.start()
        runCurrent()
        m.stop()
        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(1, calls)
    }
}
