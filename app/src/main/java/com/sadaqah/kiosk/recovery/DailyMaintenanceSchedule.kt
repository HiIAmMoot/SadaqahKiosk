package com.sadaqah.kiosk.recovery

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * When the next nightly maintenance window opens.
 *
 * Pure and separate from `MainActivity` because the loop that drives nightly
 * maintenance now depends on this repeating correctly forever, and a loop whose
 * timing nothing can test is a loop nobody can trust. Before the loop existed
 * the schedule was armed exactly once per login: a kiosk that happened to be
 * offline at 02:00 had `performReinit` return early, which meant `authenticate`
 * never ran, which meant nothing ever re-armed it — so that kiosk silently
 * stopped doing nightly reinit, auto-update maintenance *and* telemetry flushes
 * until an operator logged in by hand.
 */
object DailyMaintenanceSchedule {

    /** Local wall-clock hour the window opens. */
    const val HOUR = 2

    /**
     * Millis from [nowMs] until the next [HOUR]:00 local wall clock, always
     * strictly in the future.
     *
     * Never returns 0, and that is the load-bearing part: the caller wakes,
     * does the night's work, and asks again. If exactly 02:00:00.000 answered
     * "now", a wake that finished inside the same millisecond would run the
     * whole maintenance pass a second time. Answering "tomorrow" at the
     * boundary costs nothing — the window has already opened for today, and
     * the caller is standing in it.
     */
    fun millisUntilNext(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val todayAt = now.toLocalDate().atTime(HOUR, 0)
        val next = if (now < todayAt) todayAt else todayAt.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
