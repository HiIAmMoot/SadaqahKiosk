package com.sadaqah.kiosk.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads at most [maxChars] characters, looping until the buffer is full or the
 * stream ends.
 *
 * A single `BufferedReader.read` is best-effort — it will not block once the
 * currently-arrived bytes are consumed — so one call silently returns whatever
 * happened to be buffered. For a chunked response that is a random prefix of
 * the body, and the body is the operator's only clue about a misconfigured
 * endpoint.
 *
 * The stream is opened by [openStream] rather than handed in already open, so
 * that opening it is inside the same guard as reading it: on a connection reset
 * the success stream can throw on acquisition, and a status code we already know
 * must not be lost to that. A read that fails at any point degrades to null
 * rather than throwing, so the caller keeps the status code it already has.
 */
internal fun readCapped(openStream: () -> java.io.InputStream?, maxChars: Int): String? {
    return try {
        val stream = openStream() ?: return null
        val buffer = CharArray(maxChars)
        stream.bufferedReader().use { reader ->
            var total = 0
            while (total < maxChars) {
                val n = reader.read(buffer, total, maxChars - total)
                if (n < 0) break
                total += n
            }
            String(buffer, 0, total)
        }
    } catch (_: Throwable) {
        null
    }
}

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
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                // The headers below carry the project key. HttpURLConnection
                // replays every header onto a redirect target, and a redirect
                // target is not the host we authenticated to — so a 3xx comes
                // back as an ordinary non-success status, which is retryable.
                instanceFollowRedirects = false
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            conn = connection
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            // Supabase returns the failure reason on the error stream, and it is
            // the only clue an operator gets about a misconfigured endpoint.
            // Opening it is deferred into readCapped so a throw there costs the
            // body and not the status we already have.
            val responseBody = readCapped(
                { if (HttpResponse.isSuccess(code)) connection.inputStream else connection.errorStream },
                TelemetryRedactor.MAX_TEXT_BYTES
            )
            HttpResponse(code, responseBody)
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
}
