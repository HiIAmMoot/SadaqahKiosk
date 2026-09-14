package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping is tested here rather than through [PrefsStatusStore] because no
 * JVM test in this project can construct a Context and Robolectric is not an
 * available dependency. Everything with judgement in it lives in this object;
 * the store is the SharedPreferences plumbing around it.
 */
class StatusCodecTest {

    /**
     * What [PrefsStatusStore.write] leaves in SharedPreferences, which is NOT
     * what [StatusCodec.encode] returns: the store turns a null value into
     * `editor.remove`, so an absent table's key is **missing**, where encode
     * leaves it **present and null**. Decoding encode's raw output would
     * therefore exercise a shape the device never has — and would miss that a
     * missing key falls back to the legacy value.
     */
    private fun asStored(status: TelemetryStatus): Map<String, Any?> =
        StatusCodec.encode(status).filterValues { it != null }

    @Test
    fun `round trips per-table state through the shape the store actually writes`() {
        val status = TelemetryStatus(
            lastSuccessMs = 111L,
            lastError = "HTTP 400 bad column",
            lastErrorAtMs = 222L,
            consecutiveFailuresByTable = mapOf(TelemetryTables.DIAGNOSTICS to 3),
            backoffUntilMsByTable = mapOf(TelemetryTables.DIAGNOSTICS to 999L),
            droppedCount = 7
        )
        assertEquals(status, StatusCodec.decode(asStored(status)))
    }

    /**
     * The defect this test exists for: a table that recovers has its per-table
     * key removed, and if the pre-upgrade global keys were still on disk the
     * fallback would resurrect them — re-backing-off every recovered table and
     * pinning its failure count at the pre-upgrade value for the life of the
     * install, so the very first failure after recovery would cost the full
     * one-hour ceiling instead of a minute.
     *
     * Mutation check: stop emitting the two legacy keys from `encode` and this
     * test must fail.
     */
    @Test
    fun `a write clears the legacy keys, so a recovered table stays recovered`() {
        val upgraded = mutableMapOf<String, Any?>(
            StatusCodec.KEY_FAILURES to 6,
            StatusCodec.KEY_BACKOFF_UNTIL to 5_000L
        )

        // The migration seeds every table on the first read.
        val migrated = StatusCodec.decode(upgraded)
        assertEquals(TelemetryTables.ALL.associateWith { 6 }, migrated.consecutiveFailuresByTable)

        // Everything then recovers, and the store applies the write.
        for ((key, value) in StatusCodec.encode(migrated.copy(
            consecutiveFailuresByTable = emptyMap(),
            backoffUntilMsByTable = emptyMap()
        ))) {
            if (value == null) upgraded.remove(key) else upgraded[key] = value
        }

        assertTrue("the legacy keys must not survive a write",
            !upgraded.containsKey(StatusCodec.KEY_FAILURES) &&
            !upgraded.containsKey(StatusCodec.KEY_BACKOFF_UNTIL))
        val after = StatusCodec.decode(upgraded)
        assertEquals(emptyMap<String, Int>(), after.consecutiveFailuresByTable)
        assertEquals(emptyMap<String, Long>(), after.backoffUntilMsByTable)
    }

    /**
     * droppedCount is an operator's only view of telemetry loss and must not be
     * collateral damage from an unrelated key. Before per-key totality one
     * ClassCastException anywhere yielded a wholly default status, silently
     * zeroing it — and going from six keys to ten multiplies the ways that
     * happens.
     */
    @Test
    fun `one corrupt key costs one field and nothing else`() {
        val raw = asStored(
            TelemetryStatus(
                droppedCount = 42,
                lastSuccessMs = 111L,
                backoffUntilMsByTable = mapOf(TelemetryTables.DONATIONS to 999L)
            )
        ).toMutableMap()
        raw[StatusCodec.keyFor(StatusCodec.KEY_BACKOFF_UNTIL, TelemetryTables.DONATIONS)] = "not a long"

        val decoded = StatusCodec.decode(raw)
        assertEquals(42, decoded.droppedCount)
        assertEquals(111L, decoded.lastSuccessMs)
        assertEquals(emptyMap<String, Long>(), decoded.backoffUntilMsByTable)
    }

    @Test
    fun `a legacy single value seeds every table on first upgrade`() {
        // mapOf<String, Any> is explicit, not decorative: left inferred, Kotlin
        // unifies the vararg Pair type across both entries and silently widens
        // the bare `4` literal to Long to match `5_000L` — so raw[KEY_FAILURES]
        // would be a Long, `as? Int` would miss it, and this would assert
        // against an empty map for a reason that has nothing to do with decode.
        val decoded = StatusCodec.decode(
            mapOf<String, Any>(StatusCodec.KEY_FAILURES to 4, StatusCodec.KEY_BACKOFF_UNTIL to 5_000L)
        )
        assertEquals(TelemetryTables.ALL.associateWith { 4 }, decoded.consecutiveFailuresByTable)
        assertEquals(TelemetryTables.ALL.associateWith { 5_000L }, decoded.backoffUntilMsByTable)
    }

    @Test
    fun `a per-table key wins over the legacy key it supersedes`() {
        val decoded = StatusCodec.decode(
            mapOf(
                StatusCodec.KEY_BACKOFF_UNTIL to 5_000L,
                StatusCodec.keyFor(StatusCodec.KEY_BACKOFF_UNTIL, TelemetryTables.DONATIONS) to 9_000L
            )
        )
        assertEquals(9_000L, decoded.backoffUntilMsByTable[TelemetryTables.DONATIONS])
        assertEquals(5_000L, decoded.backoffUntilMsByTable[TelemetryTables.DIAGNOSTICS])
    }

    @Test
    fun `an absent error decodes as null`() {
        assertNull(StatusCodec.decode(emptyMap()).lastError)
    }
}
