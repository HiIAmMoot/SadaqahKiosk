package com.sadaqah.kiosk.recovery

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DailyMaintenanceScheduleTest {

    /** Fixed and offset-free, so the arithmetic below cannot be perturbed by the
     *  machine running the suite. DST is exercised separately and deliberately. */
    private val utc = ZoneId.of("UTC")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, sec: Int = 0, zone: ZoneId = utc): Long =
        LocalDateTime.of(y, m, d, h, min, sec).atZone(zone).toInstant().toEpochMilli()

    private fun hours(n: Long) = n * 60 * 60 * 1000

    @Test
    fun beforeTheWindowItWaitsUntilTheSameMorning() {
        // 00:30 → 02:00 the same day is 90 minutes.
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 0, 30), utc)
        assertEquals(90 * 60 * 1000L, wait)
    }

    @Test
    fun afterTheWindowItWaitsUntilTheNextMorning() {
        // 03:00 → 02:00 tomorrow is 23 hours.
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 3), utc)
        assertEquals(hours(23), wait)
    }

    /**
     * The boundary the loop depends on. A zero here would let a pass that
     * finished inside the same millisecond run the whole night's maintenance
     * twice; "tomorrow" is the answer that cannot do that.
     */
    @Test
    fun exactlyAtTheWindowItWaitsAFullDayRatherThanZero() {
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 2), utc)
        assertEquals(hours(24), wait)
    }

    @Test
    fun oneSecondBeforeTheWindowItWaitsOneSecond() {
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 1, 59, 59), utc)
        assertEquals(1000L, wait)
    }

    @Test
    fun oneSecondAfterTheWindowItWaitsAlmostAFullDay() {
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 9, 7, 2, 0, 1), utc)
        assertEquals(hours(24) - 1000L, wait)
    }

    /**
     * Every instant across a full day must produce a wait that is strictly
     * positive and no longer than a day. A loop that got 0 would spin; one that
     * got a negative would not delay at all.
     */
    @Test
    fun theWaitIsAlwaysPositiveAndNeverMoreThanADay() {
        var t = at(2026, 9, 7, 0)
        val end = at(2026, 9, 8, 0)
        while (t < end) {
            val wait = DailyMaintenanceSchedule.millisUntilNext(t, utc)
            assertTrue("wait must be > 0 at $t, was $wait", wait > 0)
            assertTrue("wait must be <= 24h at $t, was $wait", wait <= hours(24))
            t += 7 * 60 * 1000 // every 7 minutes
        }
    }

    /**
     * A spring-forward night skips 02:00 entirely in this zone: the clock jumps
     * 01:59:59 → 03:00:00. The schedule must still hand back a sane, positive
     * wait rather than something the loop would treat as an error, because a
     * kiosk in Amsterdam meets this twice a year.
     */
    @Test
    fun aDaylightSavingJumpOverTheWindowStillYieldsASaneWait() {
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        // 2026-03-29 is the spring-forward date in Europe/Amsterdam.
        val wait = DailyMaintenanceSchedule.millisUntilNext(at(2026, 3, 29, 1, 30, 0, amsterdam), amsterdam)
        assertTrue("wait must be positive, was $wait", wait > 0)
        assertTrue("wait must be no more than a day, was $wait", wait <= hours(24))
    }
}
