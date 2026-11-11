package app.milkbank.ui.patients

import app.milkbank.api.RecordDto
import app.milkbank.api.StaffDto
import java.time.Duration
import java.time.Instant

private val NURSE_CANCEL_WINDOW: Duration = Duration.ofMinutes(10)

/** Whether to show the cancel button. The server makes the final decision. */
fun canCancel(record: RecordDto, me: StaffDto, now: Instant): Boolean {
    if (record.kind == "cancel" || record.canceled) return false
    if (me.role == "admin") return true
    return record.staffId == me.id && Duration.between(record.createdAt, now) <= NURSE_CANCEL_WINDOW
}
