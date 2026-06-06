package com.sadaqah.kiosk.donations

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure-function helpers that turn a list of donations + a timeframe + a
 * measurement-start timestamp into the numbers the History screen displays.
 *
 * Timeframe is a fixed-length wall-clock window ending at "now". The
 * throughput averages are derived from the entries *inside that window*,
 * scaled to per-day / per-week / per-month using the elapsed time since the
 * measurement-start timestamp (clamped to the window).
 */
object DonationStats {

    enum class Timeframe { TODAY, LAST_7_DAYS, LAST_30_DAYS, LAST_YEAR, ALL_TIME }

    private const val MS_PER_HOUR = 3_600_000L
    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_WEEK = MS_PER_DAY * 7
    private const val MS_PER_MONTH = MS_PER_DAY * 30 // simple, predictable
    private const val MS_PER_YEAR = MS_PER_DAY * 365

    data class WindowSummary(
        val total: BigDecimal,
        val count: Int,
        val averagePerDonation: BigDecimal,
        val averagePerDay: BigDecimal,
        val averagePerWeek: BigDecimal,
        val averagePerMonth: BigDecimal,
        /** ms used as the denominator for the per-day/week/month math. */
        val effectiveDurationMs: Long
    )

    /** Distinct amount + how many times it occurred + the total it contributed. */
    data class FrequencyRow(val amount: BigDecimal, val count: Int, val total: BigDecimal)

    fun windowMillis(timeframe: Timeframe): Long = when (timeframe) {
        Timeframe.TODAY -> MS_PER_DAY
        Timeframe.LAST_7_DAYS -> MS_PER_WEEK
        Timeframe.LAST_30_DAYS -> MS_PER_MONTH
        Timeframe.LAST_YEAR -> MS_PER_YEAR
        Timeframe.ALL_TIME -> Long.MAX_VALUE
    }

    /**
     * Slice [entries] to the given timeframe + at-or-after [statsStartedAtMs].
     * Throughput averages are scaled to the actual elapsed measurement duration,
     * not the timeframe length — so on day 3 of using the kiosk, "last year"
     * doesn't divide by 365.
     */
    fun summarise(
        entries: List<DonationHistory.Entry>,
        timeframe: Timeframe,
        statsStartedAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): WindowSummary {
        val windowMs = windowMillis(timeframe)
        val windowStart = when (timeframe) {
            Timeframe.ALL_TIME -> 0L
            else -> nowMs - windowMs
        }
        // Effective floor = whichever of windowStart / statsStartedAtMs is later
        // (treat statsStartedAtMs == 0 as "no floor").
        val floor = if (statsStartedAtMs > 0) maxOf(windowStart, statsStartedAtMs) else windowStart
        val inWindow = entries.filter { it.timestampMs >= floor && it.timestampMs <= nowMs }

        val total = inWindow.fold(BigDecimal.ZERO) { acc, e -> acc + e.amount }
        val count = inWindow.size
        val perDonation = if (count == 0) BigDecimal.ZERO
            else total.divide(BigDecimal(count), 2, RoundingMode.HALF_UP)

        // Effective duration = clock time since the floor. Never zero (give it
        // at least 1ms) so division is safe.
        val effectiveDuration = (nowMs - floor).coerceAtLeast(1L)

        return WindowSummary(
            total = total,
            count = count,
            averagePerDonation = perDonation,
            averagePerDay = scale(total, effectiveDuration, MS_PER_DAY),
            averagePerWeek = scale(total, effectiveDuration, MS_PER_WEEK),
            averagePerMonth = scale(total, effectiveDuration, MS_PER_MONTH),
            effectiveDurationMs = effectiveDuration
        )
    }

    /** Distinct amounts sorted by frequency desc, breaking ties on higher total. */
    fun frequencyTable(
        entries: List<DonationHistory.Entry>,
        timeframe: Timeframe,
        statsStartedAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): List<FrequencyRow> {
        val windowMs = windowMillis(timeframe)
        val windowStart = if (timeframe == Timeframe.ALL_TIME) 0L else nowMs - windowMs
        val floor = if (statsStartedAtMs > 0) maxOf(windowStart, statsStartedAtMs) else windowStart
        return entries.asSequence()
            .filter { it.timestampMs >= floor && it.timestampMs <= nowMs }
            .groupBy { it.amount }
            .map { (amt, list) -> FrequencyRow(amt, list.size, list.fold(BigDecimal.ZERO) { a, e -> a + e.amount }) }
            .sortedWith(compareByDescending<FrequencyRow> { it.count }.thenByDescending { it.total })
            .toList()
    }

    /**
     * Buckets [entries] into bars for a Canvas chart. Bucket size adapts to
     * the timeframe so the chart always shows ~7–31 bars regardless of span.
     */
    data class Bar(val labelTimestampMs: Long, val total: BigDecimal)

    fun bars(
        entries: List<DonationHistory.Entry>,
        timeframe: Timeframe,
        nowMs: Long = System.currentTimeMillis()
    ): List<Bar> {
        val bucketMs: Long
        val bucketCount: Int
        when (timeframe) {
            Timeframe.TODAY -> { bucketMs = MS_PER_HOUR; bucketCount = 24 }
            Timeframe.LAST_7_DAYS -> { bucketMs = MS_PER_DAY; bucketCount = 7 }
            Timeframe.LAST_30_DAYS -> { bucketMs = MS_PER_DAY; bucketCount = 30 }
            Timeframe.LAST_YEAR -> { bucketMs = MS_PER_MONTH; bucketCount = 12 }
            Timeframe.ALL_TIME -> {
                if (entries.isEmpty()) return emptyList()
                val span = nowMs - entries.first().timestampMs
                // 12 buckets across the whole observed history; minimum bucket = 1h.
                bucketMs = (span / 12).coerceAtLeast(MS_PER_HOUR)
                bucketCount = 12
            }
        }
        val windowStart = nowMs - bucketMs * bucketCount
        val sums = LongArray(bucketCount) // store as cents-ish via scaledLong; keep BigDecimal in a parallel array
        val totals = Array(bucketCount) { BigDecimal.ZERO }
        for (e in entries) {
            if (e.timestampMs < windowStart || e.timestampMs > nowMs) continue
            val idx = ((e.timestampMs - windowStart) / bucketMs).toInt().coerceIn(0, bucketCount - 1)
            totals[idx] = totals[idx] + e.amount
            sums[idx] = sums[idx] + 1
        }
        return List(bucketCount) { i ->
            Bar(labelTimestampMs = windowStart + bucketMs * i, total = totals[i])
        }
    }

    private fun scale(total: BigDecimal, effectiveDurationMs: Long, perUnitMs: Long): BigDecimal {
        if (effectiveDurationMs <= 0) return BigDecimal.ZERO
        return total
            .multiply(BigDecimal(perUnitMs))
            .divide(BigDecimal(effectiveDurationMs), 2, RoundingMode.HALF_UP)
    }
}
