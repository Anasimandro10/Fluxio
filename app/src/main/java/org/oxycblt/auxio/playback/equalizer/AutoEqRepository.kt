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

/**
 * Repository for downloading and caching headphone EQ profiles from AutoEQ.
 * API by Jaakko Pasanen (https://autoeq.app), licensed under the MIT License.
 * See assets/licenses/autoeq_license.txt for full attribution.
 *
 * Usage:
 *  1. Call [search] to get a list of [AutoEqResult] matching the user query.
 *  2. Call [fetchProfile] with a chosen result to download and cache the gains.
 *  3. Call [getCachedBands] / [getCachedHeadphoneName] to read the cached profile.
 */
@Singleton
class AutoEqRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Searches AutoEQ for headphones matching [query].
     * Returns up to [maxResults] results, or an empty list on any error.
     */
    suspend fun search(query: String, maxResults: Int = 30): List<AutoEqResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            try {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val url = URL("$BASE_URL/headphones?name=$encoded")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "Fluxio/$APP_VERSION")
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext emptyList()
                    }
                    val body = connection.inputStream.bufferedReader().readText()
                    parseSearchResults(body, maxResults)
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Downloads the 10-band EQ profile for [result] and caches it locally.
     * Returns the [FloatArray] of gains in dB (indices 0-9), or null on failure.
     * Index 0 = 31 Hz ... index 9 = 16000 Hz, matching [EqualizerAudioProcessor].
     */
    suspend fun fetchProfile(result: AutoEqResult): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(result.id, "UTF-8")
                val url = URL("$BASE_URL/headphones/$encoded")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "Fluxio/$APP_VERSION")
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext null
                    }
                    val body = connection.inputStream.bufferedReader().readText()
                    val gains = parseProfileResponse(body) ?: return@withContext null
                    saveToCache(result.name, result.source, gains)
                    gains
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Returns the display name of the currently cached headphone, or null. */
    fun getCachedHeadphoneName(): String? = prefs.getString(KEY_HEADPHONE_NAME, null)

    /** Returns the measurement source of the cached headphone (e.g. "oratory1990"), or null. */
    fun getCachedHeadphoneSource(): String? = prefs.getString(KEY_HEADPHONE_SOURCE, null)

    /**
     * Returns the cached 10-band gains as a [FloatArray], or null if none cached.
     * Indices correspond to [TARGET_FREQUENCIES] (31, 63, 125 ... 16000 Hz).
     */
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

    /** Clears the locally cached profile. */
    fun clearCache() {
        prefs.edit()
            .remove(KEY_HEADPHONE_NAME)
            .remove(KEY_HEADPHONE_SOURCE)
            .remove(KEY_BANDS_JSON)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun parseSearchResults(body: String, maxResults: Int): List<AutoEqResult> {
        return try {
            val array = JSONArray(body)
            val results = mutableListOf<AutoEqResult>()
            for (i in 0 until minOf(array.length(), maxResults)) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name", "")
                if (name.isEmpty()) continue
                results.add(
                    AutoEqResult(
                        id = obj.optString("id", name),
                        name = name,
                        source = obj.optString("source", ""),
                    )
                )
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseProfileResponse(body: String): FloatArray? {
        return try {
            val obj = JSONObject(body)
            val graphicEqStr =
                obj.optString("graphicEq", null) ?: obj.optString("graphic_eq", null)
                    ?: return null
            parseGraphicEq(graphicEqStr)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses AutoEQ GraphicEQ format: "GraphicEQ: 20 -0.43; 25 -0.52; 31 -1.20; ..."
     * and interpolates the values at our 10 target frequencies using
     * log-frequency linear interpolation.
     */
    private fun parseGraphicEq(raw: String): FloatArray? {
        val data = raw.removePrefix("GraphicEQ:").trim()
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
            val targetFreq = TARGET_FREQUENCIES[i].toFloat()
            interpolateGain(points, targetFreq).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
    }

    /**
     * Interpolates gain at [targetFreq] using log-frequency spacing for
     * perceptually correct results across octaves.
     */
    private fun interpolateGain(
        points: List<Pair<Float, Float>>,
        targetFreq: Float,
    ): Float {
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
        prefs.edit()
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

        /** Target frequencies (Hz) matching [EqualizerAudioProcessor]. */
        val TARGET_FREQUENCIES =
            intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/** A search result returned by the AutoEQ headphone search API. */
data class AutoEqResult(
    /** API identifier used to fetch the full EQ profile. */
    val id: String,
    /** Human-readable headphone model name shown to the user. */
    val name: String,
    /** Measurement source, e.g. "oratory1990", "Rtings", "crinacle". */
    val source: String,
)
