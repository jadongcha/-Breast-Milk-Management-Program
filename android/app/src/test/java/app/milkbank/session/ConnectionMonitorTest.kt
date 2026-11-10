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
