package com.sadaqah.kiosk.telemetry

/**
 * What happened to one batch.
 *
 * [uploadedIds] and [rejectedIds] are both safe to remove from the outbox — the
 * first succeeded, the second never will. They are reported apart so the caller
 * can log the difference rather than lose it.
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
    private val anonKey: String,
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

                // A rejected batch may contain exactly one bad row. Retrying the
                // rows individually isolates it, so the rest of the queue is not
                // held hostage by a row that will never be accepted.
                response.isPermanentRejection && forTable.size > 1 -> {
                    for (event in forTable) {
                        val single = send(table, listOf(event))
                        when {
                            single.isSuccess -> uploaded += event.id
                            single.isPermanentRejection -> rejected += event.id
                            else -> {
                                retryable = true
                                lastError = describe(single)
                            }
                        }
                    }
                }

                response.isPermanentRejection -> {
                    rejected += forTable.map { it.id }
                    lastError = describe(response)
                }

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
                "apikey" to anonKey,
                "Authorization" to "Bearer $anonKey",
                "Content-Type" to "application/json",
                // The client generates each id, so a retry after an ambiguous
                // failure is absorbed rather than double-counting a donation.
                "Prefer" to "return=minimal,resolution=ignore-duplicates"
            ),
            body = events.joinToString(",", prefix = "[", postfix = "]") { it.payload }
        )

    /** Surfaced to the operator in Settings and written to logs, so it goes
     *  through the house redactor rather than a manual replace of one known
     *  value — a server can echo back tokens we did not put in the request. */
    private fun describe(response: HttpResponse): String {
        val body = TelemetryRedactor.scrub(response.body, anonKey) ?: ""
        return "HTTP ${response.code} $body".trim()
    }
}
