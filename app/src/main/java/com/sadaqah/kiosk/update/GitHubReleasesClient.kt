package com.sadaqah.kiosk.update

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads GitHub releases for a public repo. Unauthenticated — 60 req/hr is
 * plenty for once-per-day polling. All network on Dispatchers.IO.
 */
class GitHubReleasesClient(
    private val owner: String,
    private val repo: String
) {
    suspend fun listReleases(): List<ReleaseInfo> = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$owner/$repo/releases?per_page=30")
        val json = httpGet(url) ?: return@withContext emptyList()
        try {
            val arr = JsonParser.parseString(json) as? JsonArray ?: return@withContext emptyList()
            arr.mapNotNull { parseRelease(it.asJsonObject) }
        } catch (e: Exception) {
            Log.e("GitHubReleases", "parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseRelease(o: com.google.gson.JsonObject): ReleaseInfo? {
        if (o.get("draft")?.asBoolean == true) return null
        if (o.get("prerelease")?.asBoolean == true) return null
        val tag = o.get("tag_name")?.asString ?: return null
        val version = SemVer.parse(tag) ?: return null

        val assets = o.getAsJsonArray("assets") ?: return null
        val apk = assets.firstOrNull {
            val name = it.asJsonObject.get("name")?.asString.orEmpty()
            name.endsWith(".apk", ignoreCase = true)
        }?.asJsonObject ?: return null
        val apkUrl = apk.get("browser_download_url")?.asString ?: return null
        val apkSize = apk.get("size")?.asLong ?: 0L

        return ReleaseInfo(
            tag = tag,
            version = version,
            name = o.get("name")?.asString ?: tag,
            body = o.get("body")?.asString.orEmpty(),
            publishedAtIso = o.get("published_at")?.asString.orEmpty(),
            apkUrl = apkUrl,
            apkSizeBytes = apkSize
        )
    }

    private fun httpGet(url: URL): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SadaqahKiosk-Updater")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText()
            else {
                Log.w("GitHubReleases", "HTTP ${conn.responseCode} for $url")
                null
            }
        } catch (e: Exception) {
            Log.e("GitHubReleases", "GET $url failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
