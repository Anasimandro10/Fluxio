/*
 * Copyright (c) 2026 Fluxio Project
 * StereoWideningSettings.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.stereowidening

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists stereo-widening preferences and keeps [StereoWideningProcessor] in sync.
 *
 * [setAmount] writes to SharedPreferences AND updates the processor so that the value survives
 * process death regardless of whether the caller is the Preference UI or AudioTabFragment's raw
 * SeekBar.
 *
 * Follows the same pattern as [CrossfadeSettings].
 */
@Singleton
class StereoWideningSettings
@Inject
constructor(@ApplicationContext context: Context, private val processor: StereoWideningProcessor) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        /** SharedPreferences key for the stereo widening amount. */
        const val KEY_AMOUNT = "fluxio_stereo_widening"

        /** Default widening percentage. */
        const val DEFAULT_AMOUNT = 0
    }

    /** Widening percentage [0–100]. */
    val amountPercent: Int
        get() = prefs.getInt(KEY_AMOUNT, DEFAULT_AMOUNT)

    /**
     * Persists the widening intensity and pushes it to [StereoWideningProcessor].
     *
     * Writing to SharedPreferences here means the value is correct after a restart whether the
     * caller is the Preference framework or AudioTabFragment's raw SeekBar.
     *
     * @param percent Raw integer [0–100].
     */
    fun setAmount(percent: Int) {
        prefs.edit().putInt(KEY_AMOUNT, percent).apply()
        processor.amount = percent / 100f
    }

    init {
        // Sync persisted value into the processor on the first injection.
        processor.amount = amountPercent / 100f

        // Listen for Spatializer state changes to automatically bypass widening.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val spatializer = audioManager.spatializer
            processor.spatializerBypass = spatializer.isEnabled && spatializer.isAvailable

            spatializer.addOnSpatializerStateChangedListener(
                { command -> command.run() },
                object : Spatializer.OnSpatializerStateChangedListener {
                    override fun onSpatializerEnabledChanged(s: Spatializer, enabled: Boolean) {
                        processor.spatializerBypass = enabled && s.isAvailable
                    }

                    override fun onSpatializerAvailableChanged(s: Spatializer, available: Boolean) {
                        processor.spatializerBypass = s.isEnabled && available
                    }
                },
            )
        }
    }
}
