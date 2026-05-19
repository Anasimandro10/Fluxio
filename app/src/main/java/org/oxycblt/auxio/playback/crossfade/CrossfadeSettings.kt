/*
 * Copyright (c) 2026 Fluxio Project
 * CrossfadeSettings.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.crossfade

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists crossfade preferences and keeps [CrossfadeProcessor] in sync.
 *
 * [setEnabled] and [setDuration] both write to SharedPreferences AND push to the processor so
 * that the value survives process death regardless of whether the caller is the Preference UI
 * or a raw SeekBar (AudioTabFragment).
 *
 * Note: the real dual-ExoPlayer crossfade engine is implemented in a later step. The processor
 * calls here are stubs that will be wired up then.
 */
@Singleton
class CrossfadeSettings
@Inject
constructor(@ApplicationContext context: Context, private val processor: CrossfadeProcessor) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        /** SharedPreferences key for the crossfade enabled flag. */
        const val KEY_ENABLED = "fluxio_crossfade_enabled"

        /** SharedPreferences key for the crossfade duration in seconds. */
        const val KEY_DURATION = "fluxio_crossfade_duration"

        /** Default crossfade duration shown when the user first opens the slider. */
        const val DEFAULT_DURATION_SECONDS = 3
    }

    /** Whether crossfade is currently enabled. */
    val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    /** Crossfade duration in whole seconds [0–12]. */
    val durationSeconds: Int
        get() = prefs.getInt(KEY_DURATION, DEFAULT_DURATION_SECONDS)

    /**
     * Persists the enabled state and pushes it to [CrossfadeProcessor].
     *
     * Writing to SharedPreferences here means the value is correct after a restart whether the
     * caller is the Preference framework or AudioTabFragment's raw SeekBar.
     */
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        processor.enabled = value
    }

    /**
     * Persists the duration and pushes it to [CrossfadeProcessor].
     *
     * @param seconds Raw integer [0–12].
     */
    fun setDuration(seconds: Int) {
        prefs.edit().putInt(KEY_DURATION, seconds).apply()
        processor.crossfadeDurationMs = seconds * 1_000L
    }

    init {
        // Sync persisted values into the processor on the first injection.
        processor.enabled = enabled
        processor.crossfadeDurationMs = durationSeconds * 1_000L
    }
}
