/*
 * Copyright (c) 2026 Fluxio Project
 * EqualizerSettings.kt is part of Fluxio.
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

/** Persists equalizer state (enabled flag, active preset, per-band gains) in SharedPreferences. */
@Singleton
class EqualizerSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("fluxio_equalizer", Context.MODE_PRIVATE)

    /** Audio output device type used for per-device EQ preset assignment. */
    enum class DeviceType {
        BLUETOOTH,
        WIRED,
    }

    companion object {
        /** Display names for the 11 factory presets. Index matches [PRESETS]. */
        val PRESET_NAMES =
            listOf(
                "Plano",
                "Rock",
                "Pop",
                "Hip-Hop",
                "Jazz",
                "Classical",
                "Electronic",
                "Bass Boost",
                "Treble Boost",
                "Vocal",
                "Loud",
            )

        /**
         * Gain values in dB for each factory preset. Bands: 31, 63, 125, 250, 500, 1000, 2000,
         * 4000, 8000, 16000 Hz.
         */
        val PRESETS =
            arrayOf(
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), // Plano
                floatArrayOf(4f, 3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 3f), // Rock
                floatArrayOf(-1f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -2f), // Pop
                floatArrayOf(5f, 4f, 3f, 2f, 1f, 0f, -1f, -1f, 0f, 0f), // Hip-Hop
                floatArrayOf(3f, 2f, 0f, -1f, -2f, 0f, 2f, 3f, 3f, 2f), // Jazz
                floatArrayOf(4f, 3f, 2f, 0f, -2f, -2f, 0f, 2f, 3f, 4f), // Classical
                floatArrayOf(4f, 3f, 0f, -2f, -1f, 2f, 3f, 3f, 2f, 1f), // Electronic
                floatArrayOf(6f, 5f, 4f, 2f, 0f, -1f, -1f, -1f, 0f, 0f), // Bass Boost
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f), // Treble Boost
                floatArrayOf(0f, 0f, 1f, 3f, 5f, 5f, 3f, 1f, 0f, -1f), // Vocal
                floatArrayOf(5f, 4f, 2f, 0f, -1f, 0f, 2f, 3f, 4f, 5f), // Loud
            )

        /** Sentinel value meaning the user has customised at least one band. */
        const val PRESET_CUSTOM = -1

        /**
         * Sentinel value meaning "do not change the EQ when this device type connects". Used by
         * [getDevicePreset] and [setDevicePreset].
         */
        const val DEVICE_PROFILE_NONE = -2

        private const val KEY_ENABLED = "eq_enabled"
        private const val KEY_PRESET = "eq_preset"

        private fun bandKey(index: Int) = "eq_band_$index"

        private fun devicePresetKey(type: DeviceType) = "eq_device_preset_${type.name}"
    }

    /** Whether the equalizer is currently active. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /**
     * Index of the active factory preset, or [PRESET_CUSTOM] when the user has moved a slider
     * manually.
     */
    var activePreset: Int
        get() = prefs.getInt(KEY_PRESET, 0)
        set(value) = prefs.edit { putInt(KEY_PRESET, value) }

    /** Returns the current gain for each of the 10 bands (dB). */
    fun getBands(): FloatArray {
        val preset = activePreset
        return if (preset == PRESET_CUSTOM) {
            FloatArray(10) { i -> prefs.getFloat(bandKey(i), 0f) }
        } else {
            PRESETS[preset].copyOf()
        }
    }

    /** Persists per-band gains. Does not change [activePreset]. */
    fun saveBands(gains: FloatArray) {
        prefs.edit {
            for (i in gains.indices) {
                putFloat(bandKey(i), gains[i])
            }
        }
    }

    /** Selects a factory preset and persists its gains. */
    fun applyPreset(presetIndex: Int) {
        activePreset = presetIndex
        if (presetIndex != PRESET_CUSTOM) {
            saveBands(PRESETS[presetIndex])
        }
    }

    /**
     * Returns the preset index assigned to [type] when that device connects, or
     * [DEVICE_PROFILE_NONE] if no automatic switch is configured.
     */
    fun getDevicePreset(type: DeviceType): Int =
        prefs.getInt(devicePresetKey(type), DEVICE_PROFILE_NONE)

    /**
     * Assigns [preset] to [type]. When a device of that type connects, the EQ will switch to that
     * preset automatically. Pass [DEVICE_PROFILE_NONE] to disable the auto-switch.
     */
    fun setDevicePreset(type: DeviceType, preset: Int) {
        prefs.edit { putInt(devicePresetKey(type), preset) }
    }
}
