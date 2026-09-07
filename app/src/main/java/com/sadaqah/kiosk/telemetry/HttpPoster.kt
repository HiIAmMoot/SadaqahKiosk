package com.sadaqah.kiosk.telemetry

data class HttpResponse(val code: Int, val body: String?) {
    val isSuccess: Boolean get() = isSuccess(code)

    /**
     * The server refused this specific content and, on its own account, always
     * will. This is a statement about the response, not a licence to delete: see
     * [TelemetryUploader] for when a refusal is actually acted on, which requires
     * a sibling row's success in the same request as corroboration.
     *
     * Deliberately an allowlist. Endpoint-level refusals — 401 and 403 for a
     * wrong or rotated key, 404 for a missing table — say nothing about the row,
     * and treating them as permanent would delete a whole queue of donation
     * records over a configuration error. Anything not listed here is retried,
     * because keeping data we cannot send costs a little disk, and dropping data
     * we could have sent costs the record itself.
     */
    val isPermanentRejection: Boolean
        get() = code in ROW_LEVEL_REFUSALS

    /** The server already holds a row with this client-generated id — but only
     *  when the response body actually names SQLSTATE `23505` (unique_violation).
     *
     *  A bare `code == 409` is not enough: PostgREST maps the *whole* Postgres
     *  integrity-violation family onto HTTP 409, not just a duplicate primary
     *  key. `23503` (foreign_key_violation) and `23P01` (exclusion_violation)
     *  also arrive as 409, and both mean the row was **rejected and never
     *  stored** — the opposite of what this property claims. Requiring the
     *  SQLSTATE in the body is the only way to tell the two apart from here;
     *  PostgREST returns its JSON error object even under
     *  `Prefer: return=minimal`, so the body is available to check.
     *
     *  On a single-row request this is success — the donation is stored, and
     *  the retry that produced it was the safe kind. On a batch it is not:
     *  PostgREST inserts a batch as one statement, so a collision aborts the
     *  whole thing and the other rows never landed, which is why it falls back
     *  to sending them individually.
     *
     *  A 409 whose body is null, or names no SQLSTATE, or names a different
     *  one, deliberately returns `false` here. That sends it to the ordinary
     *  retryable-failure path in [TelemetryUploader.upload] (it is in neither
     *  [ROW_LEVEL_REFUSALS] nor this), so the row is kept and retried rather
     *  than deleted. **Do not "fix" that by widening this back to bare
     *  `code == 409`, and do not add 409 to [ROW_LEVEL_REFUSALS] either** — an
     *  unconfirmable 409 must stay retryable, not become a permanent rejection:
     *  we don't know the row is bad, only that we can't prove it's good, and
     *  the outbox is the only copy of a donation. A stall that the 30-day age
     *  cap eventually retires is visible and reversible; a wrongful delete is
     *  neither. */
    val isAlreadyStored: Boolean get() = code == 409 && body?.contains("23505") == true

    companion object {
        /** No HTTP status at all — DNS, socket, timeout. Distinct from any real
         *  status, which is always >= 100, so callers can treat it as retryable
         *  without special-casing null. */
        const val TRANSPORT_FAILURE = -1

        /** 400 malformed, 413 too large, 422 unprocessable — all statements about
         *  the row itself. 409 (conflict, duplicate primary key) used to live here
         *  too, but with no `Prefer: resolution=ignore-duplicates` header the only
         *  way a client-generated id collides is a retry of a row already safely
         *  stored — see [isAlreadyStored]. That is success, not a refusal, so it
         *  is handled separately rather than counted as one.
         *
         *  409 does NOT belong here either when [isAlreadyStored] can't confirm
         *  it (see that KDoc): an unconfirmed 409 is neither a proven success nor
         *  a proven refusal, so it falls through to the generic retryable branch
         *  in [TelemetryUploader.upload] and the row is kept for the next flush. */
        private val ROW_LEVEL_REFUSALS = setOf(400, 413, 422)

        /** One definition of the 2xx range, reachable from a bare status code so
         *  a caller holding only the number need not build a response to ask. */
        fun isSuccess(code: Int): Boolean = code in 200..299
    }
}

/**
 * Carries bytes and nothing else.
 *
 * It exists so upload *policy* can be tested without a socket: the decisions
 * live in [TelemetryUploader], and this interface is the only part that has to
 * touch the network. Implementations must never throw — a failed request comes
 * back as [HttpResponse.TRANSPORT_FAILURE].
 */
fun interface HttpPoster {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}
