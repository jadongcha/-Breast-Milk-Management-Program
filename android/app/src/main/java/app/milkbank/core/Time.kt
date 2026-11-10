package app.milkbank.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val KST_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SEOUL)

/** Formats in Asia/Seoul regardless of the device default time zone. */
fun formatKst(instant: Instant): String = KST_FORMAT.format(instant)
