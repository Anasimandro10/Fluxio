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
 * Each search query hits the AutoEQ search endpoint directly — no full index is downloaded or
 * cached locally. Only the selected profile is cached in SharedPreferences so it survives restarts.
 *
 * API by Jaakko Pasanen (https://autoeq.app), MIT License. See assets/licenses/autoeq_license.txt
 * for attribution.
 */
@Singleton
class AutoEqRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Searches for headphones matching [query] via the AutoEQ API.
     *
     * Returns null if the network request failed (caller should show Error state). Returns an empty
     * list if the request succeeded but no headphones matched.
     */
    suspend fun search(query: String): List<AutoEqResult>? =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
                val body =
                    downloadRaw("$BASE_URL/headphones?search=$encoded&limit=$SEARCH_LIMIT")
                        ?: return@withContext null
                parseSearchResponse(body)
            } catch (e: Exception) {
                L.e("AutoEQ search failed: ${e.message}")
                null
            }
        }

    /**
     * Downloads the 10-band EQ profile for [result] and caches it locally. Returns null on failure.
     */
    suspend fun fetchProfile(result: AutoEqResult): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                val pathEncoded =
                    result.id.split("/").joinToString("/") {
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                val body =
                    downloadRaw("$BASE_URL/headphones/$pathEncoded") ?: return@withContext null
                val gains = parseProfileResponse(body) ?: return@withContext null
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

    private fun downloadRaw(urlStr: String): String? {
        return try {
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Fluxio/$APP_VERSION")
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    L.w("AutoEQ HTTP $code for $urlStr")
                    null
                } else {
                    connection.inputStream.bufferedReader().readText()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            L.w("AutoEQ request failed for $urlStr: ${e.message}")
            null
        }
    }

    /**
     * Parses the search response from the AutoEQ API. Handles both a top-level JSONArray and a
     * wrapped JSONObject.
     */
    private fun parseSearchResponse(json: String): List<AutoEqResult> {
        return try {
            val array: JSONArray =
                try {
                    JSONArray(json)
                } catch (_: Exception) {
                    val obj = JSONObject(json)
                    obj.optJSONArray("headphones")
                        ?: obj.optJSONArray("results")
                        ?: obj.optJSONArray("data")
                        ?: return emptyList()
                }

            (0 until array.length()).mapNotNull { i ->
                try {
                    val obj = array.getJSONObject(i)
                    val name =
                        obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AutoEqResult(
                        id = obj.optString("id").ifBlank { obj.optString("path").ifBlank { name } },
                        name = name,
                        source = obj.optString("source").ifBlank { "" },
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            L.w("AutoEQ parseSearchResponse: ${e.message} — first 200: ${json.take(200)}")
            emptyList()
        }
    }

    /**
     * Parses the 10-band gains from a profile API response. Accepts a JSON object with a
     * "graphicEq" field or plain text with "GraphicEQ:".
     */
    private fun parseProfileResponse(body: String): FloatArray? {
        val graphicEqStr =
            try {
                val obj = JSONObject(body)
                obj.optString("graphicEq").ifBlank { null }
                    ?: obj.optString("graphic_eq").ifBlank { null }
                    ?: obj.optString("graphiceq").ifBlank { null }
            } catch (_: Exception) {
                null
            }

        if (graphicEqStr != null) return parseGraphicEq(graphicEqStr)

        if (body.contains("GraphicEQ:", ignoreCase = true)) return parseGraphicEq(body)

        L.w("AutoEQ parseProfileResponse: unrecognised format — first 200: ${body.take(200)}")
        return null
    }

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
        if (points.size < 2) return null
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
        private const val TIMEOUT_MS = 10_000
        private const val MAX_GAIN_DB = 12f
        private const val APP_VERSION = "4.0.10"
        private const val SEARCH_LIMIT = 20

        val TARGET_FREQUENCIES = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/** A headphone model returned by the AutoEQ search API. */
data class AutoEqResult(val id: String, val name: String, val source: String)
