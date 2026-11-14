package app.milkbank.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val KST_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SEOUL)

/** Today's calendar date in Asia/Seoul. */
fun todayKst(now: Instant): LocalDate = now.atZone(SEOUL).toLocalDate()

/** Formats in Asia/Seoul regardless of the device default time zone. */
fun formatKst(instant: Instant): String = KST_FORMAT.format(instant)

/**
 * Intake time on the pending table: "오후 2시 13분" for today (Asia/Seoul), "11월 10일 오후 2시 13분" otherwise.
 * Hour is 1-12 and minutes are not zero-padded. Independent of the device time zone.
 */
fun formatPendingTime(instant: Instant, now: Instant): String {
    val t = instant.atZone(SEOUL)
    val hour12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    val clock = "${if (t.hour < 12) "오전" else "오후"} ${hour12}시 ${t.minute}분"
    return if (t.toLocalDate() == now.atZone(SEOUL).toLocalDate()) clock else "${t.monthValue}월 ${t.dayOfMonth}일 $clock"
}
