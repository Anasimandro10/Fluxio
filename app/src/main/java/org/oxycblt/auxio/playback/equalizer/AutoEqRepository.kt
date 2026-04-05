/*
 * Copyright (c) 2026 Fluxio Project
 * AutoEqRepository.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.equalizer

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber as L

/**
 * Repository for downloading headphone EQ profiles from the AutoEQ API on demand.
 *
 * Endpoints (base = https://autoeq.app/api):
 *   Search  → GET /results/search/{query}
 *   Response: JSON array — fields can be short (n/s/i/r) or long (name/source/id/rank).
 *   Profile → GET /results/{id}
 *   Response: plain text "GraphicEQ: freq gain; freq gain; …"
 *
 * No full index is downloaded. Only the selected profile is cached in SharedPreferences.
 *
 * API by Jaakko Pasanen (https://autoeq.app), MIT License.
 * See assets/licenses/autoeq_license.txt for attribution.
 */
@Singleton
class AutoEqRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Searches for headphones matching [query] via GET /results/search/{query}.
     *
     * Returns null if the network request failed.
     * Returns an empty list if the API succeeded but no headphones matched.
     */
    suspend fun search(query: String): List<AutoEqResult>? =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
                val body =
                    downloadRaw("$BASE_URL/results/search/$encoded", acceptJson = true)
                        ?: return@withContext null
                parseSearchResponse(body)
            } catch (e: Exception) {
                L.e("AutoEQ search failed: ${e.message}")
                null
            }
        }

    /**
     * Downloads the 10-band EQ profile for [result] via GET /results/{id}.
     * Response is plain text: "GraphicEQ: freq gain; freq gain; …"
     * Returns null on failure.
     */
    suspend fun fetchProfile(result: AutoEqResult): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                val body =
                    downloadRaw("$BASE_URL/results/${result.id}", acceptJson = false)
                        ?: return@withContext null
                val gains = parseGraphicEq(body) ?: return@withContext null
                saveToCache(result.name, result.source, gains)
                gains
            } catch (e: Exception) {
                L.e("AutoEQ fetchProfile failed: ${e.message}")
                null
            }
        }

    fun getCachedHeadphoneName(): String? = prefs.getString(KEY_HEADPHONE_NAME, null)

    fun getCachedHeadphoneSource(): String? = prefs.getString(KEY_HEADPHONE_SOURCE, null)

    fun getCachedBands(): FloatArray? {
        val json = prefs.getString(KEY_BANDS_JSON, null) ?: return null
        return try {
            val arr = JSONArray(json)
            if (arr.length() != BAND_COUNT) return null
            FloatArray(BAND_COUNT) { i -> arr.getDouble(i).toFloat() }
        } catch (_: Exception) {
            null
        }
    }

    fun clearProfileCache() {
        prefs
            .edit()
            .remove(KEY_HEADPHONE_NAME)
            .remove(KEY_HEADPHONE_SOURCE)
            .remove(KEY_BANDS_JSON)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Downloads the body of [urlStr] as a String.
     * Returns null on any network error or non-200 response.
     */
    private fun downloadRaw(urlStr: String, acceptJson: Boolean): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Fluxio/$APP_VERSION")
            connection.setRequestProperty(
                "Accept",
                if (acceptJson) "application/json, */*" else "text/plain, */*",
            )
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                L.w("AutoEQ HTTP $code for $urlStr")
                null
            } else {
                connection.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            L.w("AutoEQ request failed for $urlStr: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses the search JSON response.
     * Accepts both compact field names (n/s/i/r) and full names (name/source/id/rank),
     * and handles both top-level array and wrapped object responses.
     */
    private fun parseSearchResponse(json: String): List<AutoEqResult> {
        return try {
            val trimmed = json.trim()
            val array: JSONArray =
                if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("items")
                        ?: return emptyList()
                }

            (0 until array.length()).mapNotNull { i ->
                try {
                    val obj = array.getJSONObject(i)
                    // Support both short field names (n/s/i/r) and long names (name/source/id/rank)
                    val name =
                        obj.optString("n").takeIf { it.isNotBlank() }
                            ?: obj.optString("name").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                    val id =
                        obj.optLong("i", -1L).let { short ->
                            if (short >= 0L) short else obj.optLong("id", -1L)
                        }
                    if (id < 0L) return@mapNotNull null
                    val source =
                        obj.optString("s").ifBlank { null }
                            ?: obj.optString("source").ifBlank { "" }
                    val rank =
                        obj.optInt("r", 0).let { r ->
                            if (r != 0) r else obj.optInt("rank", 0)
                        }
                    AutoEqResult(id = id, name = name, source = source, rank = rank)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            L.w("AutoEQ parseSearchResponse failed: ${e.message} — body: ${json.take(300)}")
            emptyList()
        }
    }

    /**
     * Parses the plain-text GraphicEQ response.
     * Expected: "GraphicEQ: 31 -1.5; 63 0.2; 125 2.1; …"
     * Interpolates to the 10 target frequencies.
     */
    private fun parseGraphicEq(raw: String): FloatArray? {
        val data = raw.replace("GraphicEQ:", "", ignoreCase = true).trim()
        val points = mutableListOf<Pair<Float, Float>>()
        for (token in data.split(";")) {
            val parts = token.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val freq = parts[0].toFloatOrNull() ?: continue
            val gain = parts[1].toFloatOrNull() ?: continue
            if (freq > 0f) points.add(freq to gain)
        }
        if (points.size < 2) {
            L.w("AutoEQ parseGraphicEq: too few points (${points.size}) — raw: ${raw.take(200)}")
            return null
        }
        points.sortBy { it.first }
        return FloatArray(BAND_COUNT) { i ->
            interpolateGain(points, TARGET_FREQUENCIES[i].toFloat())
                .coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
    }

    private fun interpolateGain(points: List<Pair<Float, Float>>, targetFreq: Float): Float {
        if (targetFreq <= points.first().first) return points.first().second
        if (targetFreq >= points.last().first) return points.last().second
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].first <= targetFreq) lo = mid else hi = mid
        }
        val (f0, g0) = points[lo]
        val (f1, g1) = points[hi]
        if (f1 == f0) return g0
        val t =
            ((log10(targetFreq.toDouble()) - log10(f0.toDouble())) /
                    (log10(f1.toDouble()) - log10(f0.toDouble())))
                .toFloat()
        return g0 + t * (g1 - g0)
    }

    private fun saveToCache(name: String, source: String, bands: FloatArray) {
        val arr = JSONArray()
        bands.forEach { arr.put(it.toDouble()) }
        prefs
            .edit()
            .putString(KEY_HEADPHONE_NAME, name)
            .putString(KEY_HEADPHONE_SOURCE, source)
            .putString(KEY_BANDS_JSON, arr.toString())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "fluxio_autoeq"
        private const val KEY_HEADPHONE_NAME = "autoeq_headphone_name"
        private const val KEY_HEADPHONE_SOURCE = "autoeq_headphone_source"
        private const val KEY_BANDS_JSON = "autoeq_bands_json"
        private const val BAND_COUNT = 10
        private const val BASE_URL = "https://autoeq.app/api"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_GAIN_DB = 12f
        private const val APP_VERSION = "4.0.10"

        val TARGET_FREQUENCIES = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/** A headphone model returned by the AutoEQ search API. */
data class AutoEqResult(val id: Long, val name: String, val source: String, val rank: Int = 0)
