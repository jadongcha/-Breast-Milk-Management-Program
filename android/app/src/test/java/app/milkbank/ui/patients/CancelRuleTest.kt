package app.milkbank.ui.patients

import app.milkbank.api.StaffDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CancelRuleTest {
    private val now = Instant.parse("2026-11-10T01:00:00Z")
    private val nurse = StaffDto(1, "n", "김간호", "nurse", true, false)
    private val admin = StaffDto(9, "a", "관리", "admin", true, false)
    private fun rec(staffId: Int = 1, age: Duration = Duration.ofMinutes(1), kind: String = "in", canceled: Boolean = false) =
        recordDto(1, kind = kind, staffId = staffId, createdAt = now.minus(age), canceled = canceled)

    @Test fun nurse_ownWithinTenMinutes_allowed() = assertTrue(canCancel(rec(), nurse, now))
    @Test fun nurse_exactlyTenMinutes_allowed() = assertTrue(canCancel(rec(age = Duration.ofMinutes(10)), nurse, now))
    @Test fun nurse_tenMinutesOneSecond_denied() =
        assertFalse(canCancel(rec(age = Duration.ofMinutes(10).plusSeconds(1)), nurse, now))
    @Test fun nurse_otherStaff_denied() = assertFalse(canCancel(rec(staffId = 2), nurse, now))
    @Test fun admin_anyStaffAnyAge_allowed() =
        assertTrue(canCancel(rec(staffId = 2, age = Duration.ofDays(3)), admin, now))
    @Test fun cancelKind_denied_forEveryone() {
        assertFalse(canCancel(rec(kind = "cancel"), nurse, now))
        assertFalse(canCancel(rec(kind = "cancel"), admin, now))
    }
    @Test fun alreadyCanceled_denied_forEveryone() {
        assertFalse(canCancel(rec(canceled = true), nurse, now))
        assertFalse(canCancel(rec(canceled = true), admin, now))
    }
}
