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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber as L

/**
 * Result of a LRCLIB lyrics search.
 *
 * @param syncedLyrics LRC-formatted synced lyrics, or null if unavailable.
 * @param plainLyrics Plain text lyrics, or null if unavailable.
 */
data class LrclibResult(val syncedLyrics: String?, val plainLyrics: String?)

/**
 * Fetches lyrics from the LRCLIB public API using a multi-strategy search with scoring.
 *
 * Tries up to 5 strategies in order, stopping as soon as a good-enough match is found. Each
 * candidate is scored; the highest-scoring result above the minimum threshold is returned.
 *
 * Strategies (in order):
 * 1. Cleaned track name + cleaned artist name
 * 2. Cleaned track name + first artist only (handles "Artist feat. X", "Artist & Artist2")
 * 3. Simplified track (strips remaster/live/feat suffixes) + first artist
 * 4. Generic query: "firstArtist cleanTrack"
 * 5. Generic query: title only (last resort)
 *
 * Scoring per candidate (minimum 20 to be accepted):
 * - +40 has synced lyrics
 * - +20 has plain lyrics (if no synced)
 * - +20 artist name contains match (bidirectional)
 * - +20 track name contains match (bidirectional)
 * - +15 duration within ±2 s
 * - +10 duration within ±5 s
 * - +5 duration within ±10 s Duration is a bonus, never a hard filter.
 */
@Singleton
class LrclibApi @Inject constructor() {

    private val searchUrl = "https://lrclib.net/api/search"
    private val timeoutMs = 10_000

    /**
     * Searches LRCLIB for the best matching lyrics.
     *
     * @param trackName Song title.
     * @param artistName Artist name (may contain feat., multiple artists, etc.).
     * @param albumName Album name (used for logging only).
     * @param durationSeconds Song duration in seconds (used for scoring, not filtering).
     * @return [LrclibResult] if a match was found, null otherwise.
     */
    suspend fun search(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): LrclibResult? =
        withContext(Dispatchers.IO) {
            val cleanTrack = cleanTitle(trackName)
            val cleanArtist = cleanArtist(artistName)
            val firstArtist = firstArtist(cleanArtist)
            val simpleTrack = simplifyTitle(cleanTrack)

            // Strategies — skipped automatically if identical to previous
            val strategies =
                listOf(
                    params(track = cleanTrack, artist = cleanArtist),
                    params(track = cleanTrack, artist = firstArtist),
                    params(track = simpleTrack, artist = firstArtist),
                    params(query = "$firstArtist $simpleTrack"),
                    params(query = simpleTrack),
                )

            var lastParams = ""
            for ((i, p) in strategies.withIndex()) {
                if (p == lastParams) continue
                lastParams = p

                L.d("LRCLIB strategy ${i + 1}: $p")
                val candidates = fetchRaw(p) ?: continue
                if (candidates.isEmpty()) continue

                val best =
                    pickBest(candidates, cleanArtist, firstArtist, cleanTrack, durationSeconds)
                if (best != null) {
                    L.d(
                        "LRCLIB hit on strategy ${i + 1} " +
                            "(synced=${best.syncedLyrics != null}, plain=${best.plainLyrics != null})"
                    )
                    return@withContext best
                }
            }

            L.d("LRCLIB: no match for '$trackName' by '$artistName'")
            null
        }

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    /** Fetches one page of LRCLIB results. Returns null on network error. */
    private fun fetchRaw(queryParams: String): List<JSONObject>? {
        return try {
            val connection = URL("$searchUrl?$queryParams").openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty(
                "User-Agent",
                "Fluxio/1.0 (https://github.com/Anasimandro10/fluxio)",
            )
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                L.d("LRCLIB HTTP ${connection.responseCode}")
                connection.disconnect()
                return null
            }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val arr = JSONArray(body)
            List(arr.length()) { arr.getJSONObject(it) }
        } catch (e: Exception) {
            L.e("LRCLIB network error: $e")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    /**
     * Returns the highest-scoring candidate, or null if none score above the minimum threshold
     * (20). A minimum score of 20 ensures the result has lyrics and at least one name match.
     */
    private fun pickBest(
        candidates: List<JSONObject>,
        cleanArtist: String,
        firstArtist: String,
        cleanTrack: String,
        durationSeconds: Int,
    ): LrclibResult? {
        var bestScore = 19
        var bestResult: LrclibResult? = null

        for (obj in candidates) {
            val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() }
            if (synced == null && plain == null) continue

            var score = 0

            // Lyrics type
            if (synced != null) score += 40 else score += 20

            // Name matching — bidirectional contains, case-insensitive
            val apiArtist = obj.optString("artistName").lowercase().trim()
            val apiTrack = obj.optString("trackName").lowercase().trim()
            val ca = cleanArtist.lowercase()
            val fa = firstArtist.lowercase()
            val ct = cleanTrack.lowercase()

            if (
                apiArtist.isNotEmpty() &&
                    (apiArtist.contains(ca) ||
                        ca.contains(apiArtist) ||
                        apiArtist.contains(fa) ||
                        fa.contains(apiArtist))
            )
                score += 20

            if (apiTrack.isNotEmpty() && (apiTrack.contains(ct) || ct.contains(apiTrack)))
                score += 20

            // Duration bonus — never a hard filter
            val apiDuration = obj.optInt("duration", -1)
            if (apiDuration > 0 && durationSeconds > 0) {
                val diff = Math.abs(apiDuration - durationSeconds)
                score +=
                    when {
                        diff <= 2 -> 15
                        diff <= 5 -> 10
                        diff <= 10 -> 5
                        else -> 0
                    }
            }

            if (score > bestScore) {
                bestScore = score
                bestResult = LrclibResult(syncedLyrics = synced, plainLyrics = plain)
            }
        }

        return bestResult
    }

    // -------------------------------------------------------------------------
    // Name cleaning
    // -------------------------------------------------------------------------

    /** "Song (feat. X) [Deluxe]" → "Song" */
    private fun cleanTitle(s: String) =
        s.trim().replace(Regex("\\(.*?\\)"), "").replace(Regex("\\[.*?]"), "").trim()

    /** Removes parenthetical content from artist name. */
    private fun cleanArtist(s: String) = s.trim().replace(Regex("\\(.*?\\)"), "").trim()

    /**
     * Returns the first credited artist. "Artist A feat. Artist B, Artist C & Artist D" → "Artist
     * A"
     */
    private fun firstArtist(artist: String): String =
        artist
            .split(
                Regex(
                    "\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s*[,&/]\\s*",
                    RegexOption.IGNORE_CASE,
                )
            )
            .first()
            .trim()

    /**
     * Strips release-variant suffixes from a title so "Song - Remastered 2011" and "Song" both
     * resolve to "Song".
     */
    private fun simplifyTitle(title: String): String =
        title
            .replace(
                Regex(
                    "\\s*[-–]\\s*(remaster(ed)?( \\d{4})?|live( at .+)?|acoustic|radio edit|" +
                        "single version|original mix|extended( mix)?|instrumental|demo|edit( version)?)\\s*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.+$", RegexOption.IGNORE_CASE), "")
            .trim()

    /** Encodes and assembles URL query parameters. */
    private fun params(track: String = "", artist: String = "", query: String = ""): String {
        val enc = { s: String -> URLEncoder.encode(s.trim(), "UTF-8") }
        return when {
            query.isNotBlank() -> "q=${enc(query)}"
            else ->
                buildString {
                    if (track.isNotBlank()) append("track_name=${enc(track)}")
                    if (artist.isNotBlank()) {
                        if (isNotEmpty()) append("&")
                        append("artist_name=${enc(artist)}")
                    }
                }
        }
    }
}
