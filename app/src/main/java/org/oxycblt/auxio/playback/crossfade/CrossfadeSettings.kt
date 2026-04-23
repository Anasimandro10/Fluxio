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
 * Uses the default [PreferenceManager] shared preferences so that the Preference UI
 * (SeekBarPreference / SwitchPreferenceCompat) and this class read from the same store.
 *
 * On startup the [init] block pushes the persisted values into the processor. At runtime
 * [AudioPreferenceFragment] calls [setEnabled] / [setDuration] via OnPreferenceChangeListener so
 * the processor is updated immediately without needing a singleton listener — avoiding the
 * single-listener constraint documented in the project lessons.
 */
@Singleton
class CrossfadeSettings
@Inject
constructor(@ApplicationContext context: Context, private val processor: CrossfadeProcessor) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        /** SharedPreferences key — must match set_key_crossfade_enabled in settings.xml. */
        const val KEY_ENABLED = "fluxio_crossfade_enabled"

        /** SharedPreferences key — must match set_key_crossfade_duration in settings.xml. */
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
     * Pushes a new enabled state to [CrossfadeProcessor].
     *
     * The Preference framework has already persisted [value] to SharedPreferences before this is
     * called (OnPreferenceChangeListener returns true), so [enabled] will reflect [value] on the
     * next read.
     */
    fun setEnabled(value: Boolean) {
        processor.enabled = value
    }

    /**
     * Pushes a new duration to [CrossfadeProcessor].
     *
     * [seconds] is the raw integer from the SeekBarPreference [0–12].
     */
    fun setDuration(seconds: Int) {
        processor.crossfadeDurationMs = seconds * 1_000L
    }

    init {
        // Sync persisted values into the processor on the first injection.
        processor.enabled = enabled
        processor.crossfadeDurationMs = durationSeconds * 1_000L
    }
}
