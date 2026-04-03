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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for downloading and caching headphone EQ profiles from AutoEQ.
 *
 * On first use, downloads the full headphone index from the AutoEQ API and stores it in the app
 * cache directory. Subsequent searches are local and instant. The index is refreshed every 7 days.
 * If a network attempt fails, the next search retries — there is no permanent failure flag.
 *
 * API by Jaakko Pasanen (https://autoeq.app), MIT License. See assets/licenses/autoeq_license.txt
 * for attribution.
 */
@Singleton
class AutoEqRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val indexFile = File(context.cacheDir, INDEX_FILE_NAME)

    @Volatile private var memoryIndex: List<AutoEqResult>? = null

    private val indexMutex = Mutex()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Ensures the full headphone index is available in memory. Downloads it if needed. Returns
     * false only if both the network and the on-disk cache are unavailable. The next call retries.
     */
    suspend fun ensureIndexLoaded(): Boolean {
        memoryIndex?.let { return true }
        return indexMutex.withLock { loadIndexLocked() }
    }

    /**
     * Searches the in-memory index locally. Call [ensureIndexLoaded] first.
     *
     * Results are sorted so names starting with [query] appear first, then alphabetically.
     */
    fun searchLocal(query: String, maxResults: Int = 20): List<AutoEqResult> {
        val index = memoryIndex ?: return emptyList()
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return index
            .filter { it.name.lowercase().contains(q) }
            .sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.name.lowercase() }))
            .take(maxResults)
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
            } catch (_: Exception) {
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

    fun clearIndexCache() {
        indexFile.delete()
        memoryIndex = null
    }

    // -------------------------------------------------------------------------
    // Index loading
    // -------------------------------------------------------------------------

    private suspend fun loadIndexLocked(): Boolean =
        withContext(Dispatchers.IO) {
            memoryIndex?.let { return@withContext true }

            // Use valid on-disk cache if available and fresh
            if (indexFile.exists()) {
                val ageMs = System.currentTimeMillis() - indexFile.lastModified()
                if (ageMs < INDEX_TTL_MS) {
                    val parsed = tryParseIndex(indexFile.readText())
                    if (parsed != null) {
                        memoryIndex = parsed
                        return@withContext true
                    }
                }
            }

            // Download fresh index
            val json = downloadRaw("$BASE_URL/headphones")
            if (json != null) {
                val parsed = tryParseIndex(json)
                if (parsed != null) {
                    try {
                        indexFile.writeText(json)
                    } catch (_: Exception) { /* non-fatal */ }
                    memoryIndex = parsed
                    return@withContext true
                }
            }

            // Fallback: use expired cache
            if (indexFile.exists()) {
                val parsed = tryParseIndex(indexFile.readText())
                if (parsed != null) {
                    memoryIndex = parsed
                    return@withContext true
                }
            }

            // Return false — no permanent flag, next call retries
            false
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
                if (connection.responseCode != HttpURLConnection.HTTP_OK) null
                else connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryParseIndex(json: String): List<AutoEqResult>? {
        return try {
            val array = JSONArray(json)
            if (array.length() == 0) return null
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val name =
                    obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AutoEqResult(
                    id = obj.optString("id").ifBlank { name },
                    name = name,
                    source = obj.optString("source"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseProfileResponse(body: String): FloatArray? {
        return try {
            val obj = JSONObject(body)
            val graphicEqStr =
                obj.optString("graphicEq").ifBlank { null }
                    ?: obj.optString("graphic_eq").ifBlank { null }
                    ?: return null
            parseGraphicEq(graphicEqStr)
        } catch (_: Exception) {
            null
        }
    }

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
        private const val INDEX_FILE_NAME = "autoeq_index.json"
        private const val KEY_HEADPHONE_NAME = "autoeq_headphone_name"
        private const val KEY_HEADPHONE_SOURCE = "autoeq_headphone_source"
        private const val KEY_BANDS_JSON = "autoeq_bands_json"
        private const val BAND_COUNT = 10
        private const val BASE_URL = "https://autoeq.app/api"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_GAIN_DB = 12f
        private const val APP_VERSION = "4.0.10"
        private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000

        val TARGET_FREQUENCIES = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/** A headphone model from the AutoEQ index. */
data class AutoEqResult(
    val id: String,
    val name: String,
    val source: String,
)
