package com.sadaqah.kiosk.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * The only part of the upload path that touches the network.
 *
 * Deliberately branch-free beyond success-versus-failure: every decision worth
 * testing lives in the uploader, because this class cannot be exercised without
 * a socket.
 *
 * **Blocking — call off the main thread.** On Android a main-thread call raises
 * NetworkOnMainThreadException, which the catch below would quietly convert into
 * a transport failure, so the mistake would surface as telemetry that silently
 * never uploads rather than as a crash.
 */
class UrlConnectionPoster(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) : HttpPoster {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            // Supabase returns the failure reason on the error stream, and it is
            // the only clue an operator gets about a misconfigured endpoint.
            val stream = if (HttpResponse(code, null).isSuccess) conn.inputStream else conn.errorStream
            HttpResponse(code, readCapped(stream))
        } catch (t: Throwable) {
            // Throwable rather than Exception: an unbounded response body can
            // raise OutOfMemoryError, and this must never propagate into a
            // caller sitting on the donation path.
            HttpResponse(HttpResponse.TRANSPORT_FAILURE, t.toString())
        } finally {
            // Outside the catch above, so it gets its own guard — a throw here
            // would escape and discard an already-computed response.
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * A failure body is server-controlled and unbounded — a captive portal
     * returns a whole HTML page. Anything past the cap has no diagnostic value,
     * and reading it all risks an OutOfMemoryError.
     *
     * A read that fails partway must not cost us the status code, so the body
     * degrades to null while the caller keeps the code it already has.
     */
    private fun readCapped(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return try {
            val buffer = CharArray(TelemetryRedactor.MAX_TEXT_BYTES)
            stream.bufferedReader().use { reader ->
                val read = reader.read(buffer, 0, buffer.size)
                if (read <= 0) "" else String(buffer, 0, read)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
