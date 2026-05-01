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
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists stereo-widening preferences and keeps [StereoWideningProcessor] in sync.
 *
 * Uses the default [PreferenceManager] shared preferences so that the Preference UI
 * (SeekBarPreference) and this class read from the same store.
 *
 * On startup the [init] block pushes the persisted value into the processor. At runtime
 * [AudioPreferenceFragment] calls [setAmount] via OnPreferenceChangeListener so the processor is
 * updated immediately without needing a singleton listener — avoiding the single-listener
 * constraint documented in the project lessons.
 *
 * Follows exactly the same pattern as [CrossfadeSettings].
 */
@Singleton
class StereoWideningSettings
@Inject
constructor(
    @ApplicationContext context: Context,
    private val processor: StereoWideningProcessor,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        /** SharedPreferences key — must match set_key_stereo_widening in settings.xml. */
        const val KEY_AMOUNT = "fluxio_stereo_widening"

        /** Default widening percentage shown when the user first opens the slider. */
        const val DEFAULT_AMOUNT = 0
    }

    /** Widening percentage [0–100]. */
    val amountPercent: Int
        get() = prefs.getInt(KEY_AMOUNT, DEFAULT_AMOUNT)

    /**
     * Pushes a new widening intensity to [StereoWideningProcessor].
     *
     * The Preference framework has already persisted [percent] to SharedPreferences before this is
     * called (OnPreferenceChangeListener returns true), so [amountPercent] will reflect [percent]
     * on the next read.
     *
     * @param percent The raw integer from the SeekBarPreference [0–100].
     */
    fun setAmount(percent: Int) {
        processor.amount = percent / 100f
    }

    init {
        // Sync persisted value into the processor on the first injection.
        processor.amount = amountPercent / 100f
    }
}
