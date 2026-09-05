package com.sadaqah.kiosk.telemetry

data class HttpResponse(val code: Int, val body: String?) {
    val isSuccess: Boolean get() = isSuccess(code)

    /**
     * The server refused this specific content and always will, so the row can
     * be dropped rather than retried.
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

    companion object {
        /** No HTTP status at all — DNS, socket, timeout. Distinct from any real
         *  status, which is always >= 100, so callers can treat it as retryable
         *  without special-casing null. */
        const val TRANSPORT_FAILURE = -1

        /** 400 malformed, 409 conflict, 413 too large, 422 unprocessable — all
         *  statements about the row itself. */
        private val ROW_LEVEL_REFUSALS = setOf(400, 409, 413, 422)

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
