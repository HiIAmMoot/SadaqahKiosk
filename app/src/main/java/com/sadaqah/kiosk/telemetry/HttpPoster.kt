package com.sadaqah.kiosk.telemetry

data class HttpResponse(val code: Int, val body: String?) {
    val isSuccess: Boolean get() = code in 200..299

    /** 4xx: the server understood and refused. Retrying the same bytes will not help. */
    val isPermanentRejection: Boolean get() = code in 400..499

    companion object {
        /** No HTTP status at all — DNS, socket, timeout. Distinct from any real
         *  status, which is always >= 100, so callers can treat it as retryable
         *  without special-casing null. */
        const val TRANSPORT_FAILURE = -1
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
