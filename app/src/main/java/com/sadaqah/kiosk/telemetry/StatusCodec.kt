package com.sadaqah.kiosk.telemetry

/**
 * The mapping between [TelemetryStatus] and a flat key-value store.
 *
 * Separate from [PrefsStatusStore] so everything with judgement in it — the
 * per-table key scheme, the defensiveness against a bad value, the legacy
 * migration — is a pure function a JVM test can exercise. No unit test in this
 * project can construct a Context, so a mapping left inside the store is a
 * mapping nothing checks.
 *
 * Reads go through `as?` rather than a typed getter, so a key holding the wrong
 * type (a hand-edited or malformed adb-pushed prefs file — this project
 * provisions settings that way) costs its own field and nothing else. That
 * matters most for [TelemetryStatus.droppedCount], an operator's only view of
 * telemetry loss.
 */
object StatusCodec {

    const val KEY_LAST_SUCCESS = "last_success_ms"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_ERROR_AT = "last_error_at_ms"
    const val KEY_DROPPED = "dropped_count"

    /** Also the exact keys an install from before this phase holds a single
     *  global value under. */
    const val KEY_FAILURES = "consecutive_failures"
    const val KEY_BACKOFF_UNTIL = "backoff_until_ms"

    /** The per-table key is the global one plus a dot plus the table name.
     *  Tables are a closed set in code, so a flat key needs no parser. */
    fun keyFor(base: String, table: String) = "$base.$table"

    fun decode(raw: Map<String, Any?>): TelemetryStatus = TelemetryStatus(
        queued = 0,
        lastSuccessMs = raw[KEY_LAST_SUCCESS] as? Long ?: 0L,
        lastError = raw[KEY_LAST_ERROR] as? String,
        lastErrorAtMs = raw[KEY_LAST_ERROR_AT] as? Long ?: 0L,
        consecutiveFailuresByTable = perTable(raw, KEY_FAILURES) { it as? Int },
        backoffUntilMsByTable = perTable(raw, KEY_BACKOFF_UNTIL) { it as? Long },
        droppedCount = raw[KEY_DROPPED] as? Int ?: 0
    )

    fun encode(status: TelemetryStatus): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>(
            KEY_LAST_SUCCESS to status.lastSuccessMs,
            KEY_LAST_ERROR to status.lastError,
            KEY_LAST_ERROR_AT to status.lastErrorAtMs,
            KEY_DROPPED to status.droppedCount,
            // Emitted as null on every write — the store turns null into a
            // removal — so the migration below is genuinely one-shot. Without
            // this, a recovered table's per-table key is removed while the
            // pre-upgrade global key survives, and the fallback resurrects it:
            // every recovered table is instantly backed off again, and its
            // failure count reads the pre-upgrade value forever, so the first
            // failure after recovery costs the full ceiling instead of a minute.
            KEY_FAILURES to null,
            KEY_BACKOFF_UNTIL to null
        )
        for (table in TelemetryTables.ALL) {
            out[keyFor(KEY_FAILURES, table)] = status.consecutiveFailuresByTable[table]
            out[keyFor(KEY_BACKOFF_UNTIL, table)] = status.backoffUntilMsByTable[table]
        }
        return out
    }

    /**
     * A table's own key if it has one, otherwise the pre-3d-i global value.
     *
     * The fallback is what keeps a kiosk that upgrades mid-backoff backing off
     * rather than resetting to zero and hammering a destination still refusing
     * it. It survives exactly until the first write, which deletes both legacy
     * keys.
     *
     * A null result drops the table from the map entirely: absence is the
     * healthy state, so a recovered table and a fresh one read identically.
     */
    private fun <T> perTable(
        raw: Map<String, Any?>,
        base: String,
        cast: (Any?) -> T?
    ): Map<String, T> {
        val legacy = cast(raw[base])
        return TelemetryTables.ALL.mapNotNull { table ->
            val key = keyFor(base, table)
            val value = if (raw.containsKey(key)) cast(raw[key]) else legacy
            value?.let { table to it }
        }.toMap()
    }
}
