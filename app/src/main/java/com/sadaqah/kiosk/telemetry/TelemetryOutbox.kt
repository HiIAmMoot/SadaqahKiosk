package com.sadaqah.kiosk.telemetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One line of the outbox: an opaque payload plus what the queue needs to manage it. */
data class QueuedEvent(
    val id: String,
    val table: String,
    val payload: String,
    val queuedAtMs: Long
)

/**
 * Append-only JSONL queue of unsent telemetry.
 *
 * Deliberately knows nothing about event types — it moves `(id, table, payload)`
 * triples — so a new kind of event never touches queue code, and the queue can be
 * tested without constructing one.
 *
 * Storage is separate from [com.sadaqah.kiosk.donations.DonationHistory] on
 * purpose: that file is user-facing and has a Clear button, and an operator
 * clearing their history must not silently destroy unsent telemetry.
 *
 * `payload` must already be redacted. Build it with
 * [TelemetryEvent.payloadJson], which scrubs on construction; the outbox
 * itself does not inspect what it stores.
 *
 * `append` can throw IOException — a full disk, or a failed mkdirs. It does not
 * swallow it, because it cannot log without an Android dependency. The caller
 * owns that decision, and the caller sits on the donation path.
 */
class TelemetryOutbox(
    private val file: File,
    private val maxEvents: Int = 5000,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val compactSlack: Int = 100,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long the queue may go uncompacted before an append or a peek sweeps
     *  it anyway. Not housekeeping: two callers depend in writing on the age cap
     *  retiring a row nothing else will — see [compactIfDue]. */
    private val compactIntervalMs: Long = 24L * 60 * 60 * 1000,
    /** Seamed for the same reason [clock] is: the tests that matter here assert
     *  how many times the queue is read, and there is no other way to count. */
    private val readLines: (File) -> List<String> = File::readLines,
    /** Tables whose rows are evicted last. Injected rather than named here: this
     *  class knows nothing about event types by design, and a table name inside
     *  it would be the first violation. Empty by default, so every existing
     *  caller keeps today's behaviour. */
    private val protectedTables: Set<String> = emptySet(),
    private val onDropped: (Int) -> Unit = {}
) {
    /** Locking is keyed on the file, not the instance: phase 2's crash handler
     *  may construct its own TelemetryOutbox over the same path, and two
     *  instances holding separate monitors would let a compaction silently
     *  overwrite a concurrent append. */
    private val lock: Any = lockFor(file)

    /** Keyed the same way as [lock] and guarded by it. Two TelemetryOutbox
     *  instances exist over one file, so per-instance counts would diverge on
     *  the first append; the lock already solves that problem this way. */
    private val state: QueueState = stateFor(file)

    fun append(id: String, table: String, payload: String): Unit = synchronized(lock) {
        val now = clock()
        ensureLoaded()
        file.parentFile?.mkdirs()
        // A real append never puts existing bytes at risk, so an unclean death
        // costs at most the line being written — which parseLine already skips.
        // Rewriting the file instead would place the entire queue inside a
        // truncate window on every call.
        file.appendText(serialise(QueuedEvent(id, table, payload, now)))
        // After the write, so a throwing append cannot inflate the count.
        state.rows += 1
        compactIfDue(now)
    }

    /**
     * The head of the queue, minus any row belonging to [excludeTables].
     *
     * Filtering happens **before** [limit] is applied, and that ordering is the
     * whole point: taking the first [limit] rows and then dropping the excluded
     * ones would return nothing at all while the head is excluded, and since
     * peek removes nothing the head would never advance. Rows behind an
     * excluded run would stop sending entirely.
     *
     * Default-empty, so no existing caller changes. Queue order is preserved;
     * nothing here groups or sorts by table.
     */
    fun peek(limit: Int = DEFAULT_BATCH, excludeTables: Set<String> = emptySet()): List<QueuedEvent> =
        synchronized(lock) {
            val now = clock()
            // The second trigger site, and the one that makes the interval a
            // real bound. append alone only fires under load, and the stall this
            // clears — a poison row at the head that the uploader refuses —
            // happens on a quiet kiosk that is still flushing and still being
            // refused. peek already reads the whole queue, so applying the caps
            // in the same pass costs a filter and a conditional write.
            //
            // No separate ensureLoaded() here: compactIfDue reads `state.rows`
            // itself, and on the very first call of a process rows is UNKNOWN
            // with lastCompactionMs null, which alone forces `overdue` true —
            // so the first peek still sweeps without a load in front of it.
            // Calling ensureLoaded() first would cost that boot sweep a second
            // full read of the queue, on the exact path this task exists to
            // bound. When no sweep is due, `.also` below is a refresh of an
            // already-known count, not a load.
            val rows = compactIfDue(now) ?: readAll().also { state.rows = it.size }
            rows.asSequence()
                .filterNot { it.table in excludeTables }
                .take(limit)
                .toList()
        }

    fun remove(ids: Set<String>): Unit = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        // No ensureLoaded: this sets `rows` from the list it writes, so a load
        // would be a wasted read — and with the load pure it could not compact
        // here anyway, which is the property `size` and `remove` are meant to have.
        val kept = readAll().filterNot { it.id in ids }
        writeAll(kept)
        // Not optional: remove runs after every successful flush, and
        // TelemetryManager.status() reports size() straight to the operator's
        // screen. Counters that ignored this would drift upward without limit,
        // showing a growing queue depth that does not exist.
        state.rows = kept.size
    }

    fun size(): Int = synchronized(lock) {
        ensureLoaded()
        state.rows
    }

    /** Removes the queue entirely. Used when credentials are cleared: the rows
     *  name a kiosk, so leaving them on disk would strand identified data an
     *  operator has just withdrawn the basis for holding.
     *
     *  Also removes the sibling `.tmp` [writeAll] stages compaction through: a
     *  crash between its write and its atomic move would otherwise leave a full
     *  copy of the queue on disk that nothing else ever cleans up once
     *  telemetry is off. */
    fun clear(): Unit = synchronized(lock) {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        state.rows = 0
        state.lastCompactionMs = clock()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * A pure read. It sets [QueueState.rows] and writes nothing.
     *
     * It deliberately leaves [QueueState.lastCompactionMs] at zero, which makes
     * the first `append` or `peek` of the process overdue and so buys an early
     * sweep — through the normal trigger, on a path that is allowed to write,
     * rather than from inside a read.
     *
     * Making the load itself compact is a deadlock, and it is worth knowing why
     * so nobody re-derives it: this method skips its work once `rows` is set, so
     * a `writeAll` that threw would leave `rows` at the sentinel and every later
     * append, peek, size and remove would retry the same failing write forever.
     * `writeAll` stages a full second copy of the queue before its atomic move,
     * so on a full disk it is guaranteed to fail — and a full disk is the exact
     * condition the cap exists to prevent.
     */
    private fun ensureLoaded() {
        if (state.rows != UNKNOWN) return
        state.rows = readAll().size
    }

    /** Reclaims — and now reads — in batches rather than on every append. The
     *  old comment here described only the write; the read ran on every call
     *  regardless, and that mismatch is exactly the cost this phase removes. A
     *  queue is swept when it crosses [compactSlack] over the cap or when
     *  [compactIntervalMs] has elapsed, whichever comes first. */
    private fun compactIfDue(now: Long): List<QueuedEvent>? {
        val last = state.lastCompactionMs
        val overdue = last == null ||
            now - last >= compactIntervalMs ||
            now < last                       // clock corrected backwards
        // `>=`, not `>`: the behaviour being preserved is the old
        // `discarded >= compactSlack`, which fired *at* the threshold.
        //
        // Suppressed while the last reclaim failed, and that suppression costs
        // nothing real: applyCaps caps its result at maxEvents, so a reclaim
        // that *succeeds* always leaves the count under the threshold. An
        // overCap that survives a compaction can therefore only mean the write
        // failed — so gating it here suppresses failed retries and nothing else.
        val overCap = state.rows >= maxEvents + compactSlack && !state.lastCompactionFailed
        return if (overCap || overdue) compactNow(now) else null
    }

    /** Returns the rows now on disk, so a caller that was about to read can use
     *  this instead of reading again. Null when no compaction was due. */
    private fun compactNow(now: Long): List<QueuedEvent> {
        try {
            val all = readAll()
            // Set from the read, before any write is attempted: a failed reclaim
            // below must not leave the count unknown.
            state.rows = all.size
            val kept = applyCaps(all, now)
            val discarded = all.size - kept.size
            if (discarded == 0) {
                // Nothing was attempted, so nothing is failing: a prior failed
                // reclaim must not outlive the condition that caused it, or a
                // queue drained below the cap by remove() would suppress the
                // count trigger forever, since that trigger can never clear
                // itself once nothing is left to drop.
                state.lastCompactionFailed = false
                return all
            }
            return try {
                writeAll(kept)
                state.rows = kept.size
                state.lastCompactionFailed = false
                onDropped(discarded)
                kept
            } catch (_: Throwable) {
                // A failed reclaim is swallowed, and that is not laziness.
                //
                // Propagating it on the append path would be actively wrong:
                // the row has already been written, and MainActivity treats an
                // append throw as a lost event and bumps droppedCount — so a
                // failed *reclaim* would be reported to the operator as a
                // destroyed donation that is in fact safely on disk. It would
                // also make peek — which now sweeps on this same path — and
                // size able to throw into a flush that does not wrap them
                // (TelemetryManager.kt:270, :283).
                //
                // The queue is unharmed: writeAll stages through a temp file and
                // moves atomically, so a failure leaves the previous contents in
                // place — which is what state.rows above already describes.
                state.lastCompactionFailed = true
                all
            }
        } finally {
            // Stamped on every attempt, including a failed one, and deliberately
            // not sharing the counters' condition. A device with a full disk
            // would otherwise stay permanently overdue and retry a full read and
            // a failed write on every single append, for the life of the
            // deployment, on the donation path.
            state.lastCompactionMs = now
        }
    }

    /** Caps are enforced on append so an offline kiosk sheds its oldest
     *  telemetry rather than filling the device's storage.
     *
     *  Events stamped before [PLAUSIBLE_EPOCH_FLOOR_MS] are never aged out: a
     *  kiosk with a dead RTC stamps them near 1970, and once the clock is
     *  corrected the very next append would otherwise silently delete every one
     *  of them. The count cap still bounds them. */
    private fun applyCaps(events: List<QueuedEvent>, now: Long): List<QueuedEvent> {
        val cutoff = now - maxAgeMs
        val aged = events.filter {
            it.queuedAtMs < PLAUSIBLE_EPOCH_FLOOR_MS || it.queuedAtMs >= cutoff
        }
        if (aged.size <= maxEvents) return aged

        // Every quantity comes from `aged`, the list actually being capped —
        // never from the cached row count, which describes the file *before*
        // the age filter above. Sizing a deletion from that counter would
        // over-evict, and the rows it would over-evict are donations.
        val shed = aged.size - maxEvents
        val unprotected = aged.count { it.table !in protectedTables }
        val dropUnprotected = minOf(shed, unprotected)
        // Non-zero only when the queue is all-but-entirely protected rows. That
        // is the stated tie-break: with 5000 undelivered donations and nothing
        // else, oldest-first is the only option and the kiosk has a different
        // emergency. Computing both budgets up front is what makes the result
        // exact — dropping unprotected rows and "falling through" when they run
        // out leaves the queue over its cap.
        val dropProtected = shed - dropUnprotected

        var remainingUnprotected = dropUnprotected
        var remainingProtected = dropProtected
        return aged.filter { event ->
            val isProtected = event.table in protectedTables
            when {
                !isProtected && remainingUnprotected > 0 -> { remainingUnprotected--; false }
                isProtected && remainingProtected > 0 -> { remainingProtected--; false }
                else -> true
            }
        }
    }

    private fun readAll(): List<QueuedEvent> {
        if (!file.exists()) return emptyList()
        return readLines(file).mapNotNull { parseLine(it) }
    }

    /** Writes to a sibling temp file and moves it into place, so a crash during
     *  compaction leaves the previous queue intact rather than a partial one. */
    private fun writeAll(events: List<QueuedEvent>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(events.joinToString("") { serialise(it) })
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    // Envelope keys are camelCase deliberately: this envelope is local to the
    // outbox and never leaves the device. `payload` keys are snake_case because
    // that is the wire contract the Supabase tables expect.
    private fun serialise(e: QueuedEvent): String = JsonObject().apply {
        addProperty("id", e.id)
        addProperty("table", e.table)
        addProperty("queuedAt", e.queuedAtMs)
        addProperty("payload", e.payload)
    }.toString() + "\n"

    /** A single unreadable line must not cost the whole queue, so anything that
     *  fails to parse is skipped rather than throwing. */
    private fun parseLine(line: String): QueuedEvent? {
        if (line.isBlank()) return null
        return try {
            val o = JsonParser.parseString(line) as? JsonObject ?: return null
            QueuedEvent(
                id = o.get("id")?.asString ?: return null,
                table = o.get("table")?.asString ?: return null,
                payload = o.get("payload")?.asString ?: return null,
                queuedAtMs = o.get("queuedAt")?.asLong ?: return null
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Two fields, not three. An earlier design also cached a per-table count
     *  maintained on the donation path; nothing read it, because eviction sizes
     *  itself from the list it is filtering. A counter kept current on the hot
     *  path and read by nothing reads as load-bearing to whoever comes next. */
    private class QueueState {
        var rows: Int = UNKNOWN
        /** Null means no compaction has run in this process. Null rather than
         *  zero because `now - 0` is only "overdue" when the clock is large:
         *  on a dead-RTC kiosk reading near 1970 — the case
         *  PLAUSIBLE_EPOCH_FLOOR_MS exists for — a zero stamp would silently
         *  skip the boot sweep the design depends on. */
        var lastCompactionMs: Long? = null
        /** Set when a reclaim's write failed, cleared when one succeeds. While
         *  set, the **count** trigger is suppressed and only the interval
         *  retries. Without it a device whose writes keep failing does a full
         *  read and a failed write on *every append*, forever — the count
         *  trigger cannot clear itself, because the row count it reads stays
         *  over the cap precisely because the write did not happen. */
        var lastCompactionFailed: Boolean = false
    }

    companion object {
        const val DEFAULT_BATCH = 100

        /** 2020-01-01T00:00:00Z. A timestamp below this came from an unset clock
         *  and its age cannot be trusted. */
        private const val PLAUSIBLE_EPOCH_FLOOR_MS = 1_577_836_800_000L

        /** A real row count is never negative. */
        private const val UNKNOWN = -1

        private val locksByPath = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val statesByPath = java.util.concurrent.ConcurrentHashMap<String, QueueState>()

        private fun keyFor(file: File): String = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }

        private fun lockFor(file: File): Any = locksByPath.computeIfAbsent(keyFor(file)) { Any() }

        private fun stateFor(file: File): QueueState = statesByPath.computeIfAbsent(keyFor(file)) { QueueState() }

        /** Visible for tests: the state outlives every instance, so a test that
         *  reuses a path would otherwise inherit the previous test's counts. */
        internal fun resetStateForTests() {
            statesByPath.clear()
        }
    }
}
