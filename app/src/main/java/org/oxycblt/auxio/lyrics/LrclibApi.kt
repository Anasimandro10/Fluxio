/*
 * Copyright (c) 2026 Fluxio Project
 * LrclibApi.kt is part of Fluxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.lyrics

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import timber.log.Timber as L

/**
 * Result of a LRCLIB lyrics search.
 *
 * @param syncedLyrics LRC-formatted synced lyrics, or null if unavailable.
 * @param plainLyrics Plain text lyrics, or null if unavailable.
 */
data class LrclibResult(val syncedLyrics: String?, val plainLyrics: String?)

/**
 * Fetches lyrics from the LRCLIB public API.
 *
 * Sends artist + track + album + duration to the API. No account or API key required. Uses the
 * /api/search endpoint to find the best match by duration.
 */
@Singleton
class LrclibApi @Inject constructor() {

    private val baseUrl = "https://lrclib.net/api/search"
    private val timeoutMs = 10_000

    /**
     * Searches LRCLIB for lyrics matching the given song metadata.
     *
     * @param trackName Song title.
     * @param artistName Song artist.
     * @param albumName Song album name.
     * @param durationSeconds Song duration in seconds.
     * @return [LrclibResult] if a match was found, null otherwise.
     */
    suspend fun search(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): LrclibResult? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val encoded = { s: String -> URLEncoder.encode(s, "UTF-8") }
                val query =
                    "track_name=${encoded(trackName)}" +
                        "&artist_name=${encoded(artistName)}" +
                        "&album_name=${encoded(albumName)}"
                val url = URL("$baseUrl?$query")
                L.d("LRCLIB request: $url")

                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty(
                    "User-Agent",
                    "Fluxio/1.0 (https://github.com/Anasimandro10/fluxio)",
                )

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    L.d("LRCLIB returned HTTP $responseCode")
                    return@withContext null
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                parseBestMatch(body, durationSeconds)
            } catch (e: Exception) {
                L.e("LRCLIB request failed: $e")
                null
            }
        }

    /**
     * Parses the JSON array response from LRCLIB and picks the best match.
     *
     * Picks the result whose duration is closest to [targetDurationSeconds]. Tolerance: ±2 seconds.
     */
    private fun parseBestMatch(json: String, targetDurationSeconds: Int): LrclibResult? {
        return try {
            val array = JSONArray(json)
            if (array.length() == 0) {
                L.d("LRCLIB: no results")
                return null
            }

            var bestResult: LrclibResult? = null
            var bestDiff = Int.MAX_VALUE

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val apiDuration = obj.optInt("duration", -1)
                val diff =
                    if (apiDuration >= 0) Math.abs(apiDuration - targetDurationSeconds)
                    else Int.MAX_VALUE

                if (diff <= 2 && diff < bestDiff) {
                    bestDiff = diff
                    val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() }
                    if (synced != null || plain != null) {
                        bestResult = LrclibResult(syncedLyrics = synced, plainLyrics = plain)
                    }
                }
            }

            if (bestResult == null) {
                L.d("LRCLIB: no match within ±2s of duration $targetDurationSeconds")
            } else {
                L.d(
                    "LRCLIB: found match (synced=${bestResult.syncedLyrics != null}, plain=${bestResult.plainLyrics != null})"
                )
            }
            bestResult
        } catch (e: JSONException) {
            L.e("LRCLIB JSON parse error: $e")
            null
        }
    }
}
