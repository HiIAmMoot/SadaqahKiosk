package com.sadaqah.kiosk.recovery

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DailyMaintenanceScheduleTest {

    /** Fixed and offset-free, so the plain arithmetic below cannot be perturbed
     *  by the machine running the suite. DST is exercised separately. */
    private val utc = ZoneId.of("UTC")
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, sec: Int = 0, zone: ZoneId = utc): Long =
        LocalDateTime.of(y, m, d, h, min, sec).atZone(zone).toInstant().toEpochMilli()

    private fun hours(n: Long) = n * 60 * 60 * 1000

    @Test
    fun beforeTheWindowItWaitsUntilTheSameMorning() {
        assertEquals(90 * 60 * 1000L, DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 0, 30), utc))
    }

    @Test
    fun afterTheWindowItWaitsUntilTheNextMorning() {
        assertEquals(hours(23), DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 3), utc))
    }

    /**
     * The boundary the loop depends on. A zero here would let a pass that
     * finished inside the same millisecond run the whole night twice.
     */
    @Test
    fun exactlyAtTheWindowItWaitsAFullDayRatherThanZero() {
        assertEquals(hours(24), DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 2), utc))
    }

    @Test
    fun oneSecondBeforeTheWindowItWaitsOneSecond() {
        assertEquals(1000L, DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 1, 59, 59), utc))
    }

    @Test
    fun oneSecondAfterTheWindowItWaitsAlmostAFullDay() {
        assertEquals(hours(24) - 1000L, DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 2, 0, 1), utc))
    }

    @Test
    fun theWaitIsAlwaysPositiveAndNeverMoreThanADayWithoutADstShift() {
        var t = at(2026, 9, 7, 0)
        val end = at(2026, 9, 8, 0)
        while (t < end) {
            val wait = DailyMaintenanceSchedule.millisUntilNext(t, utc)
            assertTrue("wait must be > 0 at $t, was $wait", wait > 0)
            assertTrue("wait must be <= 24h at $t, was $wait", wait <= hours(24))
            t += 7 * 60 * 1000 // every 7 minutes
        }
    }

    // ── Daylight saving ──────────────────────────────────────────────────────

    /**
     * The regression this class exists to prevent, and the one an earlier
     * version of it shipped.
     *
     * `delay()` counts real elapsed time. On the autumn fall-back night the
     * *nominal* gap between 02:00 and 02:00 is 24h but the real gap is 25h, so
     * a nominal answer wakes the caller at 01:00, runs a full maintenance pass
     * — two SumUp logins and two Bluetooth power-cycles — and then runs a
     * second one an hour later at 02:00.
     *
     * 2026-10-25 is the fall-back date in Europe/Amsterdam: 03:00 CEST becomes
     * 02:00 CET. 02:00 local that morning is ambiguous, and `atZone` resolves
     * it to the earlier offset (CEST), which is the instant the loop would
     * actually have woken at.
     */
    @Test
    fun aFallBackNightWaitsTheRealTwentyFiveHoursNotANominalTwentyFour() {
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 10, 25, 2, 0, 0, amsterdam), amsterdam)
        assertEquals(hours(25), wait)
    }

    /**
     * The mirror case, which is deliberately **not** symmetric with fall-back,
     * and the assertion below is the one to read carefully rather than assume.
     *
     * A spring-forward day is an hour shorter, so the instinct is 23h. But
     * 02:00 never happens that morning, and `java.time` maps the window onto
     * 03:00 — the next real moment it can open — which hands the hour straight
     * back. 2026-03-28 02:00 CET is 01:00 UTC; 2026-03-29 03:00 CEST is also
     * 01:00 UTC. Exactly 24h.
     *
     * The property that matters is not the number but that the window still
     * opens once, at the first real moment on or after 02:00, and never twice.
     */
    @Test
    fun aSpringForwardNightOpensTheWindowOnceAtTheNextRealMoment() {
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 3, 28, 2, 0, 0, amsterdam), amsterdam)
        assertEquals(hours(24), wait)
    }

    /**
     * Whatever the offsets do, the wait must stay bounded and positive: a loop
     * that got 0 would spin, and one that got a negative would not delay at
     * all. 25h is the widest a single shift can make it.
     */
    @Test
    fun acrossBothDstTransitionsTheWaitStaysPositiveAndBounded() {
        for ((month, day) in listOf(3 to 28, 3 to 29, 10 to 24, 10 to 25)) {
            var t = at(2026, month, day, 0, 0, 0, amsterdam)
            val end = t + hours(24)
            while (t < end) {
                val wait = DailyMaintenanceSchedule.millisUntilNext(t, amsterdam)
                assertTrue("wait must be > 0 on $month/$day at $t, was $wait", wait > 0)
                assertTrue("wait must be <= 25h on $month/$day at $t, was $wait", wait <= hours(25))
                t += 11 * 60 * 1000 // every 11 minutes
            }
        }
    }
}
