package com.sadaqah.kiosk.telemetry

/**
 * What happened to one batch.
 *
 * [uploadedIds] and [rejectedIds] are both safe to remove from the outbox — the
 * first is safely on the server, the second never will be. They are reported
 * apart so the caller can log the difference rather than lose it.
 * [uploadedIds] covers two different server answers that are the same outcome
 * for the outbox: a fresh `201` and a `409` on a single-row request, which means
 * the client-generated id — the primary key — was already stored, almost always
 * by an earlier attempt at this same row. Either way the donation is on the
 * server, so either way the id is named here rather than in [rejectedIds].
 *
 * One invariant governs everything in [rejectedIds], because the outbox is the
 * only copy of a donation and the caller deletes whatever this reports: **a row
 * is named in [rejectedIds] only when another row in the same request succeeded
 * (in either of the senses above) against the same endpoint in the same flush.**
 * That sibling success is the only evidence that separates "the server refuses
 * this row" from "the server is refusing everything right now", and without it
 * the row stays queued. Anything not proven dead is retried; the outbox's age
 * cap, not this class, is what finally retires a row nobody will ever accept.
 */
data class UploadOutcome(
    val uploadedIds: Set<String>,
    val rejectedIds: Set<String>,
    val retryableFailure: Boolean,
    val lastError: String?
)

/**
 * Turns queued events into Supabase inserts.
 *
 * All the judgement lives here rather than in the transport, so it can be
 * exercised without a socket: which rows go in which request, what a response
 * means, and how to stop one bad row from stalling everything behind it.
 */
class TelemetryUploader(
    baseUrl: String,
    // Supabase's term for this key; the underlying Postgres role it authenticates
    // as is still called `anon` (unchanged), so a 42501 error hint mentioning
    // `anon` is naming the role, not a stale key type.
    private val publishableKey: String,
    private val poster: HttpPoster
) {
    private val restRoot = baseUrl.trimEnd('/') + "/rest/v1/"

    fun upload(events: List<QueuedEvent>): UploadOutcome {
        if (events.isEmpty()) return UploadOutcome(emptySet(), emptySet(), false, null)

        val uploaded = mutableSetOf<String>()
        val rejected = mutableSetOf<String>()
        var retryable = false
        var lastError: String? = null

        // One request per table: a mixed batch cannot go to a single endpoint,
        // and a failure in one table must not discard another table's success.
        for ((table, forTable) in events.groupBy { it.table }) {
            val response = send(table, forTable)
            when {
                response.isSuccess -> uploaded += forTable.map { it.id }

                // A single-row request answering 409 is not a rejection: the
                // client-generated id is the primary key, so a collision on a
                // batch of one means this exact row is already stored — a retry
                // absorbed exactly as intended. Checked before the rejection
                // branch below, and before the size>1 guard, since forTable.size
                // == 1 here by construction (the size>1 branch is 409-or-rejection).
                response.isAlreadyStored && forTable.size == 1 ->
                    uploaded += forTable.map { it.id }

                // A rejected batch may contain exactly one bad row — and a batch
                // answering 409 is the same shape: PostgREST runs a batch insert as
                // one statement, so a collision aborts the whole thing and every
                // other row in it never landed either. Either way, retrying the
                // rows individually isolates what actually happened to each one, so
                // the rest of the queue is not held hostage by a row that will
                // never be accepted (or wrongly credited alongside one that was
                // never inserted).
                (response.isPermanentRejection || response.isAlreadyStored) && forTable.size > 1 -> {
                    val uploadedHere = mutableSetOf<String>()
                    val rejectedHere = mutableSetOf<String>()
                    for (event in forTable) {
                        val single = send(table, listOf(event))
                        when {
                            single.isSuccess || single.isAlreadyStored -> uploadedHere += event.id
                            single.isPermanentRejection -> {
                                rejectedHere += event.id
                                lastError = describe(single)
                            }
                            else -> {
                                // The network went away mid-fallback. Every
                                // remaining row would burn a full connect-plus-read
                                // timeout and stay queued regardless, so stop and
                                // let the next flush retry them.
                                retryable = true
                                lastError = describe(single)
                                break
                            }
                        }
                    }

                    uploaded += uploadedHere
                    if (uploadedHere.isEmpty()) {
                        // "One bad row among many" is the hypothesis this fallback
                        // rests on, and a row that went through is what confirms it.
                        // Without one, uniform refusal refutes the hypothesis: a
                        // stale PostgREST schema cache answers 400 for every row in
                        // the fleet, and it clears on a reload. Honouring those
                        // rejections would delete a whole batch of donations that
                        // were never inserted, so report nothing rejected and let
                        // the next flush ask again. A group that stalled partway
                        // has no success either, so its earlier rejections are
                        // discarded on the same reasoning.
                        retryable = true
                    } else {
                        rejected += rejectedHere
                    }
                }

                // A lone refused row is NOT dropped. There is no sibling whose
                // success could confirm the endpoint is healthy, so a refusal and
                // a stale schema cache look identical from here — and a kiosk on a
                // quiet evening flushes exactly one donation, which makes this the
                // common path rather than an edge case. Keeping it costs one small
                // request per flush until the outbox's age cap retires it; getting
                // it wrong costs the only record of someone's donation.
                else -> {
                    retryable = true
                    lastError = describe(response)
                }
            }
        }

        return UploadOutcome(uploaded, rejected, retryable, lastError)
    }

    private fun send(table: String, events: List<QueuedEvent>): HttpResponse =
        poster.post(
            url = restRoot + table,
            headers = mapOf(
                "apikey" to publishableKey,
                "Authorization" to "Bearer $publishableKey",
                "Content-Type" to "application/json",
                // The client generates each id, which is the primary key on the
                // target table, so a retry after an ambiguous failure collides on
                // that key rather than double-counting a donation — the row is
                // simply already there, and the server says so with 409 (see
                // HttpResponse.isAlreadyStored). Deliberately NOT
                // "resolution=ignore-duplicates" or "return=representation":
                // both make PostgREST take its upsert path, which requires SELECT
                // on the table, and the device key is granted INSERT only — the
                // combination is what makes a leaked key (assumed leaked, since
                // it ships on every kiosk) worthless to read with. Requesting
                // either returns 401 (verified against the live project).
                "Prefer" to "return=minimal"
            ),
            body = events.joinToString(",", prefix = "[", postfix = "]") { it.payload }
        )

    /** Surfaced to the operator in Settings and written to logs, so it goes
     *  through the house redactor rather than a manual replace of one known
     *  value — a server can echo back tokens we did not put in the request. */
    private fun describe(response: HttpResponse): String {
        val body = TelemetryRedactor.truncate(
            TelemetryRedactor.scrub(response.body, publishableKey)
        ) ?: ""
        return "HTTP ${response.code} $body".trim()
    }
}
