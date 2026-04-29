/*
 * Copyright (c) 2026 Fluxio Project
 * ListeningModeManager.kt is part of Fluxio.
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
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named snapshot of the complete equalizer state, saved by the user as a reusable listening mode.
 *
 * @param id Unique identifier (Unix timestamp in milliseconds at creation time).
 * @param name User-provided display name.
 * @param enabled Whether the EQ was active when the snapshot was taken.
 * @param preset Factory preset index active at save time, or [EqualizerSettings.PRESET_CUSTOM].
 * @param bands Gain values (dB) for each of the 10 EQ bands.
 * @param autoEqName Name of the active AutoEQ headphone profile, or null if none was applied
 *   without subsequent manual edits.
 */
data class ListeningMode(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val preset: Int,
    val bands: FloatArray,
    val autoEqName: String?,
) {
    // FloatArray does not participate in structural equality — override manually.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ListeningMode) return false
        return id == other.id &&
            name == other.name &&
            enabled == other.enabled &&
            preset == other.preset &&
            bands.contentEquals(other.bands) &&
            autoEqName == other.autoEqName
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + preset
        result = 31 * result + bands.contentHashCode()
        result = 31 * result + (autoEqName?.hashCode() ?: 0)
        return result
    }
}

/**
 * Persists and restores named EQ snapshots ("listening modes") in SharedPreferences.
 *
 * Each mode captures the complete equalizer state at the moment of saving: the enabled flag, active
 * preset index, per-band gains, and the AutoEQ profile name (only if the profile was applied and
 * the user has not moved any slider since then). Modes are stored as a JSON array. Up to
 * [MAX_MODES] modes can coexist.
 */
@Singleton
class ListeningModeManager @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "fluxio_listening_modes"
        private const val KEY_MODES = "modes"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_ENABLED = "enabled"
        private const val FIELD_PRESET = "preset"
        private const val FIELD_BANDS = "bands"
        private const val FIELD_AUTOEQ = "autoEqName"

        /** Maximum number of listening modes the user can save simultaneously. */
        const val MAX_MODES = 10
    }

    /** Returns all saved listening modes ordered by creation time (oldest first). */
    fun getModes(): List<ListeningMode> {
        val json = prefs.getString(KEY_MODES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> parseModeFromJson(array.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Saves the supplied EQ state as a new listening mode named [name].
     *
     * @return The created [ListeningMode], or null when [MAX_MODES] has already been reached.
     */
    fun saveMode(
        name: String,
        enabled: Boolean,
        preset: Int,
        bands: FloatArray,
        autoEqName: String?,
    ): ListeningMode? {
        val current = getModes().toMutableList()
        if (current.size >= MAX_MODES) return null
        val mode =
            ListeningMode(
                id = System.currentTimeMillis(),
                name = name.trim(),
                enabled = enabled,
                preset = preset,
                bands = bands.copyOf(),
                autoEqName = autoEqName,
            )
        current.add(mode)
        persist(current)
        return mode
    }

    /** Permanently deletes the mode with [id]. No-op when the id is not found. */
    fun deleteMode(id: Long) {
        persist(getModes().filter { it.id != id })
    }

    /** Renames the mode with [id] to [newName]. No-op when the id is not found. */
    fun renameMode(id: Long, newName: String) {
        persist(getModes().map { if (it.id == id) it.copy(name = newName.trim()) else it })
    }

    // ---- Serialization ----

    private fun persist(modes: List<ListeningMode>) {
        val array = JSONArray()
        modes.forEach { array.put(modeToJson(it)) }
        prefs.edit { putString(KEY_MODES, array.toString()) }
    }

    private fun modeToJson(mode: ListeningMode): JSONObject =
        JSONObject().apply {
            put(FIELD_ID, mode.id)
            put(FIELD_NAME, mode.name)
            put(FIELD_ENABLED, mode.enabled)
            put(FIELD_PRESET, mode.preset)
            val bandsArray = JSONArray()
            mode.bands.forEach { bandsArray.put(it.toDouble()) }
            put(FIELD_BANDS, bandsArray)
            if (mode.autoEqName != null) put(FIELD_AUTOEQ, mode.autoEqName)
            else put(FIELD_AUTOEQ, JSONObject.NULL)
        }

    private fun parseModeFromJson(obj: JSONObject): ListeningMode {
        val bandsArray = obj.getJSONArray(FIELD_BANDS)
        val bands = FloatArray(bandsArray.length()) { i -> bandsArray.getDouble(i).toFloat() }
        return ListeningMode(
            id = obj.getLong(FIELD_ID),
            name = obj.getString(FIELD_NAME),
            enabled = obj.getBoolean(FIELD_ENABLED),
            preset = obj.getInt(FIELD_PRESET),
            bands = bands,
            autoEqName = if (obj.isNull(FIELD_AUTOEQ)) null else obj.optString(FIELD_AUTOEQ),
        )
    }
}
