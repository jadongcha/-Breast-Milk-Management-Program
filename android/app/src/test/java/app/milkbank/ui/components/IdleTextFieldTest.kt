package app.milkbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleTextFieldTest {
    @Test fun everyChangeTouchesIdleTimerThenForwards() {
        val log = mutableListOf<String>()
        val handler = idleAwareChange({ log += "touch" }) { log += "change:$it" }
        handler("a")
        handler("ab")
        assertEquals(listOf("touch", "change:a", "touch", "change:ab"), log)
    }
}
