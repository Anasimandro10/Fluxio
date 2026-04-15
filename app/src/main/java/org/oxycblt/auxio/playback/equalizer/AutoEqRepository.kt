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
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber as L

/**
 * Repository for downloading headphone EQ profiles from the AutoEQ GitHub repository.
 *
 * It uses the raw githubusercontent to access INDEX.md which contains a markdown list of all ~9000
 * headphone profiles, and parses it in-memory. Then it fetches the GraphicEQ.txt directly using the
 * parsed path.
 *
 * API by Jaakko Pasanen (https://autoeq.app), MIT License. See assets/licenses/autoeq_license.txt
 * for attribution.
 */
@Singleton
class AutoEqRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** In-memory cache of all profiles. Populated once per process lifetime. */
    @Volatile private var allProfilesCache: List<AutoEqResult>? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Downloads the complete list of headphone profiles via GitHub's INDEX.md. The result is cached
     * in memory so subsequent calls return immediately. Returns null if the network request failed.
     */
    suspend fun loadAllProfiles(): List<AutoEqResult>? {
        allProfilesCache?.let {
            return it
        }
        return withContext(Dispatchers.IO) {
            try {
                val body =
                    downloadRaw("$BASE_URL/INDEX.md", acceptJson = false) ?: return@withContext null
                val list = parseIndexResponse(body)
                if (list.isEmpty()) return@withContext null
                allProfilesCache = list
                list
            } catch (e: Exception) {
                L.e("AutoEQ loadAllProfiles failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Downloads the 10-band EQ profile for [result] via GitHub Raw. Response is plain text:
     * "GraphicEQ: freq gain; freq gain; …" Returns null on failure.
     */
    suspend fun fetchProfile(result: AutoEqResult): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                // The path from INDEX.md is already URL-encoded (spaces → %20, etc.)
                // e.g. "./crinacle/711%20in-ear/Apple%20AirPods%20Pro%202"
                // We just strip the "./" prefix and use it directly — do NOT re-encode.
                val pathClean = result.path.removePrefix("./")
                // The filename is built from the un-encoded display name, so we encode it.
                val fileEncoded = Uri.encode("${result.name} GraphicEQ.txt")

                val url = "$BASE_URL/$pathClean/$fileEncoded"

                val body = downloadRaw(url, acceptJson = false) ?: return@withContext null
                val gains = parseGraphicEq(body) ?: return@withContext null
                saveToCache(result.name, result.source, gains)
                addRecentProfile(result)
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

    fun getRecentProfiles(): List<AutoEqResult> {
        val json = prefs.getString(KEY_RECENT_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val name =
                    obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path =
                    obj.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val source = obj.optString("source")
                AutoEqResult(name = name, path = path, source = source)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addRecentProfile(result: AutoEqResult) {
        val current = getRecentProfiles().toMutableList()
        current.removeAll { it.path == result.path }
        current.add(0, result)
        if (current.size > 5) {
            current.removeAt(current.size - 1)
        }

        try {
            val arr = JSONArray()
            current.forEach {
                val obj = org.json.JSONObject()
                obj.put("name", it.name)
                obj.put("path", it.path)
                obj.put("source", it.source)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_RECENT_PROFILES, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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

    private fun parseIndexResponse(markdown: String): List<AutoEqResult> {
        return try {
            val lines = markdown.lines()
            val resultList = mutableListOf<AutoEqResult>()
            // Example lines from INDEX.md:
            //   - [1Custom SA02](./crinacle/711%20in-ear/1Custom%20SA02) by crinacle on 711
            //   - [1MORE Aero (ANC
            // Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by
            // HypetheSonics on GRAS RA0045
            // Note: names/paths may contain parentheses, so we use greedy (.+) for the
            // path group and let the engine backtrack to the last ") by " boundary.
            val regex = Regex("""^- \[([^\]]+)]\((.+)\)\s+by\s+(.+)$""")

            for (line in lines) {
                val match = regex.find(line.trim())
                if (match != null) {
                    val name = match.groupValues[1].trim()
                    val path = match.groupValues[2].trim()
                    val source = match.groupValues[3].trim()
                    resultList.add(AutoEqResult(name = name, path = path, source = source))
                }
            }
            resultList
        } catch (e: Exception) {
            L.w("AutoEQ parseIndexResponse failed: ${e.message}")
            emptyList()
        }
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
        private const val KEY_RECENT_PROFILES = "autoeq_recent_profiles"
        private const val BAND_COUNT = 10
        private const val BASE_URL =
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_GAIN_DB = 12f
        private const val APP_VERSION = "4.0.10"

        val TARGET_FREQUENCIES = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/** A headphone model parsed from the AutoEQ github repository. */
data class AutoEqResult(val name: String, val path: String, val source: String, val rank: Int = 0)
