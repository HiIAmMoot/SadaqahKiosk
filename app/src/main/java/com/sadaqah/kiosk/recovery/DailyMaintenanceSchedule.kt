package com.sadaqah.kiosk.recovery

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * When the next nightly maintenance window opens.
 *
 * Pure and separate from `MainActivity` because the loop that drives nightly
 * maintenance depends on this repeating correctly forever, and a loop whose
 * timing nothing can test is a loop nobody can trust. Before that loop existed
 * the schedule was armed once per login: a kiosk that happened to be offline at
 * 02:00 had `performReinit` return early, which meant `authenticate` never ran,
 * which meant nothing re-armed it — so that kiosk silently stopped doing nightly
 * reinit and stopped installing updates until its process next started.
 */
object DailyMaintenanceSchedule {

    /** Local wall-clock hour the window opens. */
    const val HOUR = 2

    /**
     * Real elapsed millis from [nowMs] until the next [HOUR]:00 **local wall
     * clock**, always strictly in the future.
     *
     * Computed between *instants*, not between `LocalDateTime`s, and that
     * distinction is the whole point. `delay()` counts real elapsed time, so a
     * nominal answer disagrees with it exactly on the nights the clocks move.
     * On an autumn fall-back night the nominal gap is 24h but the real gap is
     * 25h; a nominal answer would wake the caller an hour early at 01:00, run a
     * full maintenance pass, then compute another hour and run a *second* pass
     * at 02:00 — two SumUp logins and two Bluetooth power-cycles in one night.
     * Working in instants means the wait lands on 02:00 local whatever the
     * offset does in between, so the result may legitimately be 23h or 25h.
     *
     * Never returns 0 or less, which the caller relies on: it wakes, does the
     * night's work, and asks again. If exactly 02:00:00.000 answered "now", a
     * pass that finished inside the same millisecond would run the whole night
     * again. Answering "tomorrow" at the boundary costs nothing — the window has
     * already opened, and the caller is standing in it.
     *
     * On a spring-forward night 02:00 does not exist at all; `java.time` maps it
     * onto 03:00, which is the next real moment the window can open.
     */
    fun millisUntilNext(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val todayAt = now.toLocalDate().atTime(HOUR, 0).atZone(zone)
        val next = if (now.isBefore(todayAt)) {
            todayAt
        } else {
            now.toLocalDate().plusDays(1).atTime(HOUR, 0).atZone(zone)
        }
        return Duration.between(now.toInstant(), next.toInstant()).toMillis()
    }
}
