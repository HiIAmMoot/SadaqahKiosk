package com.sadaqah.kiosk.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * The only part of the upload path that touches the network.
 *
 * Deliberately branch-free beyond success-versus-failure: every decision worth
 * testing lives in [TelemetryUploader], because this class cannot be exercised
 * without a socket.
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
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            HttpResponse(code, text)
        } catch (e: Exception) {
            HttpResponse(HttpResponse.TRANSPORT_FAILURE, e.message)
        } finally {
            conn?.disconnect()
        }
    }
}
